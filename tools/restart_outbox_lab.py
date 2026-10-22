import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description="Kill separate JVMs at three durable outbox boundaries")
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--postgres-bin", type=Path, required=True)
parser.add_argument("--jar", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output = args.output.resolve()
args.output.mkdir(parents=True, exist_ok=False)
(args.output / "results.json").write_text(json.dumps({"passed": False, "stage": "validating_inputs"}) + "\n")
environment = {key: value for key, value in os.environ.items()
               if key in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL")}
results = {"passed": False, "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
           "platform": os.uname().sysname, "cases": []}

def persist():
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")

def port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]

pg_port = port()

def sql(statement):
    return subprocess.check_output([str(args.postgres_bin / "psql"), "-X", "-A", "-t",
        "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-p", str(pg_port),
        "-U", "ridge", "-d", "ridge", "-c", statement], text=True).strip()

def state():
    return json.loads(sql("SELECT json_build_object("
        "'reservations',(SELECT count(*) FROM reservation),"
        "'outbox',(SELECT count(*) FROM reservation_outbox),"
        "'pending',(SELECT count(*) FROM reservation_outbox WHERE delivered_at IS NULL),"
        "'inbox',(SELECT count(*) FROM consumer_inbox),"
        "'quantity',(SELECT quantity FROM fulfillment_total WHERE tenant_id='tenant-a'),"
        "'attempts',(SELECT attempts FROM reservation_outbox))"))

def launch(phase, marker, log):
    command = [str(args.java_home / "bin/java"),
        "-Dloader.main=press.tenoridge.labs.CrashRecoveryLab", "-cp", str(args.jar.resolve()),
        "org.springframework.boot.loader.launch.PropertiesLauncher",
        "--spring.config.location=classpath:/application.properties", "--server.port=0",
        f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{pg_port}/ridge",
        "--spring.datasource.username=ridge", "--spring.datasource.password=",
        f"--ridge.lab.phase={phase}", f"--ridge.lab.marker={marker}"]
    return subprocess.Popen(command, env=environment, stdout=log, stderr=subprocess.STDOUT)

def await_marker(process, marker):
    deadline = time.monotonic() + 30
    while not marker.exists():
        if process.poll() is not None:
            raise RuntimeError(f"Child exited {process.returncode} before marker; inspect log")
        if time.monotonic() >= deadline:
            raise TimeoutError("Child did not reach durable boundary")
        time.sleep(0.02)

persist()
with tempfile.TemporaryDirectory(prefix="ridge-restart-", dir="/tmp") as temporary:
    data = Path(temporary) / "data"
    with (args.output / "postgres-lifecycle.log").open("w") as lifecycle:
        subprocess.run([str(args.postgres_bin / "initdb"), "-D", str(data), "-A", "trust",
                        "-U", "ridge", "--no-locale"], check=True, stdout=lifecycle, stderr=subprocess.STDOUT)
        subprocess.run([str(args.postgres_bin / "pg_ctl"), "-D", str(data),
            "-l", str(args.output / "postgres.log"), "-o",
            f"-h 127.0.0.1 -p {pg_port} -k {temporary}", "-w", "start"],
            check=True, stdout=lifecycle, stderr=subprocess.STDOUT)
        try:
            subprocess.run([str(args.postgres_bin / "createdb"), "-h", "127.0.0.1",
                "-p", str(pg_port), "-U", "ridge", "ridge"], check=True)
            for phase in ("before-effect", "after-effect", "after-delivery"):
                if results["cases"]:
                    sql("DELETE FROM consumer_inbox; DELETE FROM reservation_outbox; DELETE FROM reservation; "
                        "UPDATE fulfillment_total SET quantity=0")
                marker = args.output / (phase + ".marker")
                with (args.output / (phase + ".log")).open("w") as log:
                    process = launch(phase, marker, log)
                    try:
                        await_marker(process, marker)
                        before = state()
                        os.kill(process.pid, signal.SIGKILL)
                        assert process.wait(timeout=10) == -signal.SIGKILL
                    finally:
                        if process.poll() is None:
                            process.kill()
                            process.wait(timeout=10)
                after_kill = state()
                expected_effect = 0 if phase == "before-effect" else 2
                assert after_kill == {"reservations": 1, "outbox": 1,
                    "pending": 0 if phase == "after-delivery" else 1,
                    "inbox": 0 if phase == "before-effect" else 1,
                    "quantity": expected_effect, "attempts": 0}, after_kill
                recovery_marker = args.output / (phase + "-recovered.marker")
                with (args.output / (phase + "-recovered.log")).open("w") as log:
                    recovered = launch("recover", recovery_marker, log)
                    try:
                        assert recovered.wait(timeout=30) == 0
                    finally:
                        if recovered.poll() is None:
                            recovered.kill()
                            recovered.wait(timeout=10)
                after_restart = state()
                assert after_restart == {"reservations": 1, "outbox": 1, "pending": 0,
                                         "inbox": 1, "quantity": 2, "attempts": 0}, after_restart
                assert marker.read_text().split()[0] == recovery_marker.read_text().split()[0]
                with (args.output / (phase + "-diagnostics.txt")).open("w") as diagnostics:
                    subprocess.run([str(args.postgres_bin / "psql"), "-X", "-v", "ON_ERROR_STOP=1",
                        "-h", "127.0.0.1", "-p", str(pg_port), "-U", "ridge", "-d", "ridge",
                        "-f", str(Path(__file__).resolve().parents[1] / "labs/tenant-diagnostics.sql")],
                        check=True, stdout=diagnostics, stderr=subprocess.STDOUT)
                results["cases"].append({"phase": phase, "killed_pid": process.pid,
                    "restarted_pid": recovered.pid, "kill_exit": process.returncode,
                    "before_kill": before, "after_kill": after_kill, "after_restart": after_restart,
                    "same_reservation": True, "recovery_marker": recovery_marker.read_text()})
                persist()
            sql("DELETE FROM fulfillment_total WHERE tenant_id='tenant-a'")
            for name, expected in (("missing-effect", "missing_effect_row"), ("wrong-quantity", "quantity_mismatch")):
                if name == "wrong-quantity":
                    sql("INSERT INTO fulfillment_total VALUES ('tenant-a',9)")
                observed = subprocess.check_output([str(args.postgres_bin / "psql"), "-X", "-v", "ON_ERROR_STOP=1",
                    "-h", "127.0.0.1", "-p", str(pg_port), "-U", "ridge", "-d", "ridge",
                    "-f", str(Path(__file__).resolve().parents[1] / "labs/tenant-diagnostics.sql")], text=True)
                (args.output / (name + "-diagnostic.txt")).write_text(observed)
                assert expected in observed, observed
            sql("UPDATE fulfillment_total SET quantity=2 WHERE tenant_id='tenant-a'")
            assert state() == {"reservations": 1, "outbox": 1, "pending": 0,
                               "inbox": 1, "quantity": 2, "attempts": 0}
            results["diagnostic_negative_fixtures"] = {
                "missing_effect_row_detected": True, "wrong_quantity_detected": True,
                "original_state_restored_and_compared": True,
                "diagnostic_sql_sha256": hashlib.sha256(
                    (Path(__file__).resolve().parents[1] / "labs/tenant-diagnostics.sql").read_bytes()).hexdigest()}
            persist()
        finally:
            subprocess.run([str(args.postgres_bin / "pg_ctl"), "-D", str(data), "-m", "fast",
                "-w", "stop"], check=True, stdout=lifecycle, stderr=subprocess.STDOUT)
results["passed"] = True
persist()
print(json.dumps(results, indent=2))
