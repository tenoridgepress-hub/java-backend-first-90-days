import argparse
import atexit
import hashlib
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description="Isolated PostgreSQL contention, resumable backfill and restore drill")
parser.add_argument("--pg-bin", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
observations = {"passed": False, "checks": {}}
def persist():
    (args.output / "postgres-recovery.json").write_text(json.dumps(observations, indent=2) + "\n")
persist()
atexit.register(persist)

with tempfile.TemporaryDirectory(prefix="ridge-recovery-") as temporary:
    directory = Path(temporary)
    data = directory / "data"
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    connection = ["-h", "127.0.0.1", "-p", str(port), "-U", "ridge"]
    with (args.output / "postgres-recovery.log").open("w") as log:
        def run(program, extra, **options):
            return subprocess.run([str(args.pg_bin / program), *extra], check=True, timeout=30,
                                  stdout=log, stderr=subprocess.STDOUT, **options)

        def sql(statement, database="ridge_lab", check=True):
            command = [str(args.pg_bin / "psql"), "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
                       *connection, "-d", database, "-c", statement]
            result = subprocess.run(command, text=True, capture_output=True, timeout=15)
            log.write(statement + "\n" + result.stdout + result.stderr)
            log.flush()
            if check and result.returncode:
                raise RuntimeError(result.stderr)
            return result

        run("initdb", ["-D", str(data), "-A", "trust", "-U", "ridge", "--no-locale"])
        run("pg_ctl", ["-D", str(data), "-l", str(directory / "server.log"), "-o",
                       f"-h 127.0.0.1 -p {port} -k {directory}", "-w", "start"])
        try:
            for name in ("ridge_lab", "ridge_restored"):
                run("createdb", [*connection, name])
            run("psql", ["-X", "-v", "ON_ERROR_STOP=1", *connection, "-d", "ridge_lab", "-f",
                         str(Path(__file__).resolve().parents[1] / "labs/recovery.sql")])
            batch = """BEGIN;
WITH batch AS (
 SELECT id FROM migration_case
 WHERE id > (SELECT last_id FROM backfill_progress WHERE job='normalize-ref')
 ORDER BY id LIMIT 500
), changed AS (
 UPDATE migration_case SET normalized_ref = lower(trim(customer_ref))
 WHERE id IN (SELECT id FROM batch) RETURNING id
)
UPDATE backfill_progress SET last_id = COALESCE((SELECT max(id) FROM changed), last_id)
WHERE job='normalize-ref';
COMMIT;"""
            for index in range(2):
                sql(batch)
            assert sql("SELECT last_id FROM backfill_progress").stdout.strip() == "1000"
            checkpoint_before = sql("SELECT last_id FROM backfill_progress").stdout.strip()
            failed = sql("BEGIN; UPDATE migration_case SET normalized_ref='broken' WHERE id=1001; SELECT 1/0; COMMIT;", check=False)
            assert failed.returncode != 0
            assert sql("SELECT normalized_ref IS NULL FROM migration_case WHERE id=1001").stdout.strip() == "t"
            assert sql("SELECT last_id FROM backfill_progress").stdout.strip() == checkpoint_before
            for index in range(18):
                sql(batch)
            mismatch = sql("SELECT count(*) FROM migration_case WHERE normalized_ref IS DISTINCT FROM lower(trim(customer_ref))").stdout.strip()
            assert mismatch == "0"
            observations["checks"]["backfill"] = {"rows": 10000, "batch_rows": 500, "committed_batches": 20,
                "restart_after_id": 1000, "failed_batch_rolled_back": True, "mismatches": 0}

            holder_command = [str(args.pg_bin / "psql"), "-X", *connection, "-d", "ridge_lab", "-c",
                "SET application_name='ridge-lock-holder'; BEGIN; UPDATE migration_case SET customer_ref=customer_ref WHERE id=1; SELECT pg_sleep(8); COMMIT;"]
            holder = subprocess.Popen(holder_command, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 5
                while sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='ridge-lock-holder' AND wait_event='PgSleep'").stdout.strip() != "1":
                    if time.monotonic() >= deadline:
                        raise TimeoutError("lock holder did not reach sleep")
                    time.sleep(0.05)
                timeout = sql("SET lock_timeout='150ms'; UPDATE migration_case SET customer_ref=customer_ref WHERE id=1", check=False)
                assert timeout.returncode != 0 and "55P03" in timeout.stderr
                observations["checks"]["contention"] = {"lock_timeout_ms": 150, "sqlstate": "55P03"}
            finally:
                holder.wait(timeout=12)

            checksum_sql = "SELECT md5(string_agg(id::text || ':' || customer_ref || ':' || normalized_ref, '|' ORDER BY id)) FROM migration_case"
            checksum = sql(checksum_sql).stdout.strip()
            archive = args.output / "recovery.dump"
            run("pg_dump", [*connection, "-d", "ridge_lab", "-Fc", "-f", str(archive)])
            sql("TRUNCATE migration_case")
            assert sql("SELECT count(*) FROM migration_case").stdout.strip() == "0"
            run("pg_restore", [*connection, "-d", "ridge_restored", "--exit-on-error", str(archive)])
            restored = sql(checksum_sql, "ridge_restored").stdout.strip()
            assert checksum == restored
            assert sql("SELECT last_id FROM backfill_progress", "ridge_restored").stdout.strip() == "10000"
            sql("BEGIN; UPDATE migration_case SET normalized_ref='bad'; ROLLBACK;", "ridge_restored")
            assert sql(checksum_sql, "ridge_restored").stdout.strip() == checksum
            sql("INSERT INTO migration_case(id, customer_ref) VALUES (10001, 'old-writer')", "ridge_restored")
            assert sql("SELECT normalized_ref IS NULL FROM migration_case WHERE id=10001", "ridge_restored").stdout.strip() == "t"
            observations["checks"]["restore"] = {"restored_rows_before_old_writer": 10000,
                "source_checksum": checksum, "restored_checksum": restored, "checkpoint_restored": 10000,
                "dump_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "transaction_rollback_verified": True, "old_column_insert_accepted": True}
            observations["passed"] = True
        finally:
            run("pg_ctl", ["-D", str(data), "-m", "fast", "-w", "stop"])
            shutil.copy2(directory / "server.log", args.output / "postgres-recovery-server.log")

(args.output / "postgres-recovery.json").write_text(json.dumps(observations, indent=2) + "\n")
print(json.dumps(observations, indent=2))
