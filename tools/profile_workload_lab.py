import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description="Local timed workload with JFR and timeout recovery observations")
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--jar", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output = args.output.resolve()
args.output.mkdir(parents=True, exist_ok=False)
(args.output / "profile-workload.json").write_text(json.dumps({"passed": False, "stage": "validating_inputs"}) + "\n")
artifact_hash = hashlib.sha256(args.jar.read_bytes()).hexdigest()
state = {"slow": False, "active": 0, "peak": 0, "disconnects": 0}
guard = threading.Lock()

class Peer(BaseHTTPRequestHandler):
    def do_GET(self):
        with guard:
            state["active"] += 1
            state["peak"] = max(state["peak"], state["active"])
            slow = state["slow"]
        try:
            body = b'{"cents":499}'
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body[:1])
            self.wfile.flush()
            if slow:
                time.sleep(1.2)
            self.wfile.write(body[1:])
        except (BrokenPipeError, ConnectionResetError):
            with guard:
                state["disconnects"] += 1
        finally:
            with guard:
                state["active"] -= 1

    def log_message(self, *values):
        pass

def token(scope):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=")
    unsigned = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode({
        "iss": "ridge-local", "sub": "profile-lab", "aud": ["ridge-orders"], "tenant_id": "tenant-a",
        "scope": scope, "exp": int(time.time()) + 600})
    return (unsigned + b"." + base64.urlsafe_b64encode(hmac.new(
        b"local-training-key-32-bytes-minimum-never-deploy", unsigned, hashlib.sha256).digest()).rstrip(b"=")).decode()

class LabServer(ThreadingHTTPServer):
    request_queue_size = 128

peer = LabServer(("127.0.0.1", 0), Peer)
threading.Thread(target=peer.serve_forever, daemon=True).start()
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
base = f"http://127.0.0.1:{port}"
read_token, ops_token = token("orders.read"), token("ops")

def call(path, bearer=read_token):
    started = time.perf_counter()
    request = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + bearer,
        "X-Request-Id": "profile-lab"})
    try:
        response = urllib.request.urlopen(request, timeout=5)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        body = json.load(response)
        return response.status, (time.perf_counter() - started) * 1000, body

def snapshot():
    result = {}
    for name in ("ridge.shipping.available.permits", "jvm.threads.live", "jvm.memory.used"):
        status, elapsed, body = call("/actuator/metrics/" + name, ops_token)
        assert status == 200
        result[name] = body["measurements"]
    with guard:
        result["peer"] = dict(state)
    return result

def phase(name, seconds, slow):
    with guard:
        state["slow"] = slow
    deadline = time.monotonic() + seconds
    def worker(index):
        samples = []
        while time.monotonic() < deadline:
            status, elapsed, body = call("/api/orders/1001/shipping")
            if status == 200:
                assert body["cents"] == 499
            else:
                assert status in (502, 503, 504), (status, body)
                assert body["code"] == {502: "upstream_failure", 503: "unavailable", 504: "upstream_timeout"}[status]
            samples.append((status, elapsed))
            time.sleep(0.01)
        return samples
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=16) as pool:
        samples = [sample for batch in pool.map(worker, range(16)) for sample in batch]
    counts = {str(status): sum(item[0] == status for item in samples) for status in (200, 502, 503, 504)}
    elapsed = sorted(item[1] for item in samples)
    return {"phase": name, "workers": 16, "requested_seconds": seconds,
        "wall_seconds": round(time.monotonic() - started, 3), "completed": len(samples), "statuses": counts,
        "client_ms": {"p50": round(elapsed[len(elapsed) // 2], 3),
                      "p95_nearest_rank": round(elapsed[math.ceil(len(elapsed) * .95) - 1], 3),
                      "max": round(elapsed[-1], 3)}, "snapshot": snapshot()}

environment = {name: value for name, value in os.environ.items()
    if name in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ", "SYSTEMROOT")}
environment.update({"PORT": str(port), "SHIPPING_URL": f"http://127.0.0.1:{peer.server_port}",
    "SHIPPING_TIMEOUT_MS": "250", "SHIPPING_MAX_IN_FLIGHT": "8",
    "LOGGING_LEVEL_PRESS_TENORIDGE_ORDERS": "DEBUG"})
recording = args.output / "workload.jfr"
results = {"passed": False, "artifact_sha256": artifact_hash,
    "limits": "18-second closed-loop loopback lab; client, JVM and peer share host. No capacity, leak or remote-cancellation proof.",
    "phases": []}
try:
    with (args.output / "profile-application.log").open("w") as log:
        process = subprocess.Popen([str(args.java_home / "bin/java"),
            f"-XX:StartFlightRecording=filename={recording},settings=profile,dumponexit=true", "-jar", str(args.jar.resolve()),
            "--spring.config.location=classpath:/application.properties",
            "--spring.datasource.url=jdbc:h2:mem:profilelab;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "--spring.datasource.username=sa", "--spring.datasource.password="],
            env=environment, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 40
            while True:
                try:
                    if call("/actuator/health")[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("application did not become healthy")
                time.sleep(.1)
            results["baseline"] = snapshot()
            for name, seconds, slow in (("healthy", 5, False), ("slow-body", 8, True), ("recovery", 5, False)):
                results["phases"].append(phase(name, seconds, slow))
            assert results["phases"][1]["statuses"]["504"] > 0
            assert results["phases"][1]["statuses"]["503"] > 0
            for attempt in range(5):
                assert call("/api/orders/1001/shipping")[0] == 200
            time.sleep(1.4)
            results["drained"] = snapshot()
            assert results["drained"]["peer"]["active"] == 0
            assert results["drained"]["ridge.shipping.available.permits"][0]["value"] == 8
            for filename, command in (("threads-after.txt", ["Thread.print", "-l"]),
                                      ("jfr-dump.txt", ["JFR.dump", f"filename={recording}"])):
                with (args.output / filename).open("w") as observed:
                    subprocess.run([str(args.java_home / "bin/jcmd"), str(process.pid), *command], check=True,
                        stdout=observed, stderr=subprocess.STDOUT, timeout=10, env=environment)
            results["workload_passed"] = True
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
finally:
    peer.shutdown()
    peer.server_close()
    (args.output / "profile-workload.json").write_text(json.dumps(results, indent=2) + "\n")

for filename, command in (("jfr-summary.txt", ["summary", str(recording)]),
                          ("jfr-samples.json", ["print", "--json", "--events",
                           "jdk.ExecutionSample,jdk.SocketRead,jdk.ThreadCPULoad,jdk.GarbageCollection", str(recording)])):
    with (args.output / filename).open("w") as observed:
        subprocess.run([str(args.java_home / "bin/jfr"), *command], check=True, stdout=observed, timeout=20, env=environment)
results["export_sha256"] = {name: hashlib.sha256((args.output / name).read_bytes()).hexdigest()
    for name in ("workload.jfr", "jfr-summary.txt", "jfr-samples.json")}
results["passed"] = True
(args.output / "profile-workload.json").write_text(json.dumps(results, indent=2) + "\n")
print(json.dumps(results, indent=2))
