import argparse
import atexit
import base64
import hashlib
import hmac
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def token(scope):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=")
    unsigned = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode({
        "iss": "ridge-local", "sub": "release-lab", "scope": scope, "exp": int(time.time()) + 600})
    signature = hmac.new(b"local-training-key-32-bytes-minimum-never-deploy", unsigned, hashlib.sha256).digest()
    return (unsigned + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()


parser = argparse.ArgumentParser(description="Local H2 release, migration, rollback and shipping rehearsal")
parser.add_argument("--previous-jar", type=Path, required=True)
parser.add_argument("--candidate-jar", type=Path, default=Path("target/ridge-orders-0.3.0.jar"))
parser.add_argument("--evidence", type=Path, default=Path("release-evidence"))
arguments = parser.parse_args()
arguments.evidence.mkdir(parents=True, exist_ok=False)
records = {"passed": False, "database": "H2 persistent file, isolated temporary directory", "stages": [], "shipping": []}
def persist():
    (arguments.evidence / "release-results.json").write_text(json.dumps(records, indent=2) + "\n")
persist()
atexit.register(persist)
for label, artifact in [("previous", arguments.previous_jar), ("candidate", arguments.candidate_jar)]:
    records[label] = {"name": artifact.name, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}
port = available_port()
delay = 0


class Stub(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        time.sleep(delay)
        payload = b'{"cents":499,"currency":"USD"}'
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *values):
        pass


stub = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stub)
threading.Thread(target=stub.serve_forever, daemon=True).start()


def call(path, scope=None, payload=None):
    headers = {"X-Request-Id": "release-lab"}
    if scope:
        headers["Authorization"] = "Bearer " + token(scope)
    if payload is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request("http://127.0.0.1:" + str(port) + path,
        headers=headers, data=None if payload is None else json.dumps(payload).encode())
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(request, timeout=5)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        body = response.read().decode()
        return {"status": response.code, "body": json.loads(body) if body else None,
                "request_id": response.headers.get("X-Request-Id"),
                "elapsed_ms": round((time.monotonic() - started) * 1000, 1)}


try:
    with tempfile.TemporaryDirectory(prefix="ridge-release-") as temporary:
        original_id = None
        second_id = None
        for stage, artifact in [("baseline-v2", arguments.previous_jar),
                                ("candidate-v3", arguments.candidate_jar),
                                ("rollback-previous-on-v3", arguments.previous_jar)]:
            environment = {key: value for key, value in os.environ.items()
                           if key in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL")}
            environment.update({"DB_URL": "jdbc:h2:file:" + str(Path(temporary) / "orders") + ";MODE=PostgreSQL",
                "DB_USER": "sa", "DB_PASSWORD": "", "PORT": str(port),
                "SHIPPING_URL": "http://127.0.0.1:" + str(stub.server_port), "SHIPPING_TIMEOUT_MS": "250"})
            environment.pop("SPRING_FLYWAY_TARGET", None)
            if stage == "baseline-v2":
                environment["SPRING_FLYWAY_TARGET"] = "2"
            with (arguments.evidence / (stage + ".log")).open("w") as log:
                process = subprocess.Popen(["java", "-jar", str(artifact.resolve()),
                    "--spring.config.location=classpath:/application.properties",
                    "--spring.datasource.url=" + environment["DB_URL"],
                    "--spring.datasource.username=sa", "--spring.datasource.password=",
                    "--server.port=" + str(port)],
                    env=environment, stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 45
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError(stage + " stopped before health; inspect log")
                        try:
                            if call("/actuator/health")["status"] == 200:
                                break
                        except (OSError, urllib.error.URLError):
                            pass
                        if time.monotonic() > deadline:
                            raise TimeoutError(stage + " did not become healthy")
                        time.sleep(0.2)
                    health = call("/actuator/health")
                    read = call("/api/orders/1001", "orders.read")
                    denied = call("/api/orders/1001/reservations", "orders.read", {"eventId": "denied", "quantity": 2})
                    first = call("/api/orders/1001/reservations", "orders.write", {"eventId": "release-1001", "quantity": 2})
                    assert read["status"] == 200 and denied["status"] == 403 and first["status"] == 200
                    if original_id is None:
                        original_id = first["body"]["id"]
                    assert first["body"]["id"] == original_id
                    result = {"name": stage, "health": health, "read": read, "denied": denied, "replay": first}
                    if stage != "baseline-v2":
                        second = call("/api/orders/1002/reservations", "orders.write", {"eventId": "release-1002", "quantity": 3})
                        assert second["status"] == 200
                        if second_id is None:
                            second_id = second["body"]["id"]
                        assert second["body"]["id"] == second_id
                        result["candidate_written_row"] = second
                    if stage == "candidate-v3":
                        for label, pause, expected in [("healthy", 0, 200), ("slow", 0.8, 504), ("recovered", 0, 200)]:
                            delay = pause
                            observed = call("/api/orders/1001/shipping", "orders.read")
                            assert observed["status"] == expected
                            records["shipping"].append({"case": label, **observed})
                    records["stages"].append(result)
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
    records["passed"] = True
finally:
    stub.shutdown()
    (arguments.evidence / "release-results.json").write_text(json.dumps(records, indent=2) + "\n")
print(json.dumps(records, indent=2))
