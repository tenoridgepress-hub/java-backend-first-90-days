import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description="Disposable PostgreSQL V5/current-application upgrade and restore")
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--pg-bin", type=Path, required=True)
parser.add_argument("--previous-jar", type=Path, required=True)
parser.add_argument("--candidate-jar", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output = args.output.resolve()
args.output.mkdir(parents=True, exist_ok=False)
(args.output / "results.json").write_text(json.dumps({"passed": False, "stage": "validating_inputs"}) + "\n")
results = {"passed": False, "fixture_rows": 200, "stopped_old_writer_required": True,
           "automatic_external_delivery": False, "platform": os.uname().sysname, "stages": []}
for name, artifact in (("previous", args.previous_jar), ("candidate", args.candidate_jar)):
    results[name] = {"path": str(artifact.resolve()), "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}

def persist():
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")

def port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]

pg_port = port()
app_port = port()
connection = ["-h", "127.0.0.1", "-p", str(pg_port), "-U", "ridge"]
environment = {key: value for key, value in os.environ.items() if key in ("PATH", "HOME", "TMPDIR", "LANG")}

def pg(program, *extra):
    completed = subprocess.run([str(args.pg_bin / program), *extra], capture_output=True, text=True, timeout=40)
    with (args.output / "postgres-commands.log").open("a") as log:
        log.write(repr([program, *extra]) + "\n" + completed.stdout + completed.stderr)
    if completed.returncode:
        raise RuntimeError(program + " failed; inspect postgres-commands.log")
    return completed.stdout.strip()

def sql(statement, database="ridge"):
    return pg("psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", *connection, "-d", database, "-c", statement)

def token(tenant):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=")
    unsigned = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode({
        "iss": "ridge-local", "sub": "upgrade-lab", "aud": ["ridge-orders"], "tenant_id": tenant,
        "scope": "orders.read orders.write", "exp": int(time.time()) + 600})
    signature = hmac.new(b"local-training-key-32-bytes-minimum-never-deploy", unsigned, hashlib.sha256).digest()
    return (unsigned + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()

def call(path, tenant="tenant-a", payload=None):
    headers = {"Authorization": "Bearer " + token(tenant)}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request("http://127.0.0.1:" + str(app_port) + path, headers=headers,
        data=None if payload is None else json.dumps(payload).encode())
    try:
        response = urllib.request.urlopen(request, timeout=5)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        return {"status": response.code, "body": json.loads(response.read())}

def command(artifact, database, main=None):
    options = ["--spring.config.location=classpath:/application.properties",
        "--server.port=" + str(app_port), "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + str(pg_port) + "/" + database,
        "--spring.datasource.username=ridge", "--spring.datasource.password="]
    if main:
        return [str(args.java_home / "bin/java"), "-Dloader.main=" + main, "-cp", str(artifact.resolve()),
                "org.springframework.boot.loader.launch.PropertiesLauncher", *options]
    return [str(args.java_home / "bin/java"), "-jar", str(artifact.resolve()), *options]

def start(artifact, database, label):
    log = (args.output / (label + ".log")).open("w")
    process = subprocess.Popen(command(artifact, database), env=environment, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 40
        while True:
            if process.poll() is not None:
                raise RuntimeError(label + " exited; inspect log")
            try:
                if call("/actuator/health")["status"] == 200:
                    return process, log
            except (OSError, ValueError):
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError(label)
            time.sleep(0.1)
    except BaseException:
        stop(process, log)
        raise

def stop(process, log):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    log.close()

def reserve(order, event, quantity, tenant="tenant-a"):
    result = call("/api/orders/" + str(order) + "/reservations", tenant, {"eventId": event, "quantity": quantity})
    assert result["status"] == 200, result
    return result["body"]

def snapshot(database):
    return json.loads(sql("SELECT json_build_object(" +
        "'orders',(SELECT json_agg(c ORDER BY id) FROM customer_order c)," +
        "'reservation',(SELECT json_agg(r ORDER BY tenant_id,event_id) FROM reservation r)," +
        "'outbox',(SELECT json_agg(o ORDER BY tenant_id,event_id) FROM reservation_outbox o)," +
        "'inbox',(SELECT json_agg(i ORDER BY tenant_id,event_id) FROM consumer_inbox i)," +
        "'totals',(SELECT json_agg(t ORDER BY tenant_id) FROM fulfillment_total t)," +
        "'legacy_total',(SELECT json_agg(l ORDER BY id) FROM fulfillment_total_legacy l))", database))

persist()
with tempfile.TemporaryDirectory(prefix="ridge-current-upgrade-", dir="/tmp") as temporary:
    data = Path(temporary) / "data"
    pg("initdb", "-D", str(data), "-A", "trust", "-U", "ridge", "--no-locale")
    pg("pg_ctl", "-D", str(data), "-l", str(args.output / "postgres.log"), "-o",
       f"-h 127.0.0.1 -p {pg_port} -k {temporary}", "-w", "start")
    try:
        for name in ("ridge", "restored_v5", "restored_v6"):
            pg("createdb", *connection, name)
        old, log = start(args.previous_jar, "ridge", "old-v5")
        try:
            original = reserve(1001, "application-before", 2)
        finally:
            stop(old, log)
        assert old.poll() is not None
        results["old_writer_exit"] = old.returncode
        sql("INSERT INTO customer_order (id,customer_ref,quantity,tenant_id) " +
            "SELECT 10000+n,'history-'||n,1,CASE WHEN n%2=0 THEN 'tenant-a' ELSE 'tenant-b' END FROM generate_series(1,200) n;" +
            "INSERT INTO reservation (id,order_id,event_id,quantity) SELECT 'fixture-'||n,10000+n,'history-'||n,1 FROM generate_series(1,200) n;" +
            "INSERT INTO reservation_outbox (event_id,reservation_id,order_id,quantity) SELECT event_id,id,order_id,quantity FROM reservation WHERE order_id>10000;" +
            "INSERT INTO consumer_inbox (event_id,reservation_id,order_id,quantity) SELECT event_id,id,order_id,quantity FROM reservation WHERE order_id>10000 AND order_id<=10100;" +
            "UPDATE reservation_outbox SET delivered_at=CURRENT_TIMESTAMP WHERE order_id>10000 AND order_id<=10100;" +
            "UPDATE fulfillment_total SET quantity=100")
        pre_dump = args.output / "before-v6.dump"
        pg("pg_dump", *connection, "-d", "ridge", "-Fc", "-f", str(pre_dump))
        candidate, log = start(args.candidate_jar, "ridge", "candidate-v6")
        try:
            assert call("/api/orders/10001", "tenant-b")["status"] == 200
            assert call("/api/orders/10001", "tenant-a")["status"] == 404
            assert reserve(1001, "application-before", 2) == original
            accepted = reserve(1002, "application-after", 3)
            results["stages"].append({"name": "candidate", "preserved": original, "accepted_after_backup": accepted})
        finally:
            stop(candidate, log)
        before_dispatch = snapshot("ridge")
        assert len(before_dispatch["reservation"]) == 202
        assert sorted(item["quantity"] for item in before_dispatch["totals"]) == [50, 50]
        with (args.output / "manual-delivery.log").open("w") as log:
            subprocess.run(command(args.candidate_jar, "ridge", "press.tenoridge.labs.MaintenanceDeliveryLab"),
                env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=45, check=True)
        after_dispatch = snapshot("ridge")
        assert len(after_dispatch["inbox"]) == 202
        assert sum(item["quantity"] for item in after_dispatch["totals"]) == 205
        keyed_totals = {item["tenant_id"]: item["quantity"] for item in after_dispatch["totals"]}
        assert keyed_totals == {"tenant-a": 105, "tenant-b": 100}
        assert sql("SELECT count(*) FROM reservation_outbox producer FULL OUTER JOIN consumer_inbox receipt " +
            "USING (tenant_id,event_id) WHERE producer.event_id IS NULL OR receipt.event_id IS NULL " +
            "OR producer.reservation_id<>receipt.reservation_id OR producer.order_id<>receipt.order_id " +
            "OR producer.quantity<>receipt.quantity") == "0"
        assert sql("SELECT count(*) FROM reservation_outbox producer JOIN reservation result " +
            "ON producer.tenant_id=result.tenant_id AND producer.reservation_id=result.id " +
            "WHERE producer.event_id<>result.event_id OR producer.order_id<>result.order_id " +
            "OR producer.quantity<>result.quantity") == "0"
        assert sql("SELECT count(*) FROM reservation_outbox WHERE delivered_at IS NULL") == "0"
        results["backfill"] = {"reservations": 202, "receipts_before": 100, "tenant_totals_before": [50, 50],
                               "receipts_after_manual_delivery": 202, "quantity_after": 205,
                               "tenant_totals_after": keyed_totals, "producer_receipt_mismatches": 0,
                               "producer_reservation_mismatches": 0}
        post_dump = args.output / "after-v6.dump"
        pg("pg_dump", *connection, "-d", "ridge", "-Fc", "-f", str(post_dump))
        pg("pg_restore", *connection, "-d", "restored_v6", "--exit-on-error", str(post_dump))
        assert snapshot("restored_v6") == after_dispatch
        restored, log = start(args.candidate_jar, "restored_v6", "restored-current")
        try:
            assert call("/api/orders/10001", "tenant-b")["status"] == 200
            assert call("/api/orders/10001", "tenant-a")["status"] == 404
            assert reserve(1001, "application-before", 2) == original
            assert reserve(1002, "application-after", 3) == accepted
        finally:
            stop(restored, log)
        assert snapshot("restored_v6") == after_dispatch
        pg("pg_restore", *connection, "-d", "restored_v5", "--exit-on-error", str(pre_dump))
        rollback, log = start(args.previous_jar, "restored_v5", "rollback-old-on-v5")
        try:
            assert reserve(1001, "application-before", 2) == original
            assert sql("SELECT count(*) FROM reservation WHERE event_id='application-after'", "restored_v5") == "0"
        finally:
            stop(rollback, log)
        rejected = subprocess.run([str(args.pg_bin / "psql"), "-X", "-v", "ON_ERROR_STOP=1",
            *connection, "-d", "ridge", "-c",
            "INSERT INTO reservation (id,order_id,event_id,quantity) VALUES ('old-writer',1002,'old-after-v6',3)"],
            capture_output=True, text=True, timeout=10)
        (args.output / "old-insert-rejected.txt").write_text(rejected.stdout + rejected.stderr)
        assert rejected.returncode != 0 and "tenant_id" in rejected.stderr
        results["restore"] = {"v6_all_business_rows_equal": True, "both_api_identities_preserved": True,
            "order_rows_and_legacy_total_compared": True, "restored_tenant_b_read_and_foreign_404_verified": True,
            "v5_old_binary_replay_preserved": True, "v5_loses_post_backup_acceptance": True,
            "missing_accepted_work": accepted, "transparent_rollback_safe": False, "old_insert_rejected_on_v6": True}
        for archive in (pre_dump, post_dump):
            results[archive.name] = hashlib.sha256(archive.read_bytes()).hexdigest()
        (args.output / "before-dispatch.json").write_text(json.dumps(before_dispatch, indent=2) + "\n")
        (args.output / "after-dispatch.json").write_text(json.dumps(after_dispatch, indent=2) + "\n")
        results["passed"] = True
    finally:
        pg("pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop")
        persist()
print(json.dumps(results, indent=2))
