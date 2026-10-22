import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import hmac
import json
import os
import platform
from pathlib import Path
import socket
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description="Repeated shipping timeout/recovery and deliberate TCP reset observations")
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--jar", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--peer-protocol", choices=("HTTP/1.0", "HTTP/1.1"), default="HTTP/1.0")
args = parser.parse_args()
args.output = args.output.resolve()
args.output.mkdir(parents=True, exist_ok=False)
(args.output / "results.json").write_text(json.dumps({"passed": False, "stage": "validating_inputs"}) + "\n")
artifact_hash = hashlib.sha256(args.jar.read_bytes()).hexdigest()
guard = threading.Lock()
state = {"mode": "fast", "active": 0, "peak": 0, "disconnects": 0, "accepted": 0, "resets": 0}
events = []

class Peer(BaseHTTPRequestHandler):
    protocol_version = args.peer_protocol

    def do_GET(self):
        with guard:
            mode = state["mode"]
            state["active"] += 1
            state["peak"] = max(state["peak"], state["active"])
            state["accepted"] += 1
            if state["accepted"] <= 8:
                events.append({"event": "request_headers", "time": time.time(),
                    "version": self.request_version, "headers": dict(self.headers),
                    "client": list(self.client_address)})
        try:
            if mode == "reset":
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                self.close_connection = True
                with guard:
                    state["resets"] += 1
                    events.append({"time": time.time(), "event": "server_abortive_close",
                                   "client": list(self.client_address)})
                return
            body = b'{"cents":499}'
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body[:1])
            self.wfile.flush()
            if mode == "slow":
                time.sleep(1.2)
            self.wfile.write(body[1:])
        except (BrokenPipeError, ConnectionResetError) as failure:
            with guard:
                state["disconnects"] += 1
                events.append({"time": time.time(), "event": type(failure).__name__, "mode": mode})
        finally:
            with guard:
                state["active"] -= 1

    def log_message(self, *values):
        pass

class LabServer(ThreadingHTTPServer):
    request_queue_size = 128
    daemon_threads = True

peer = LabServer(("127.0.0.1", 0), Peer)
threading.Thread(target=peer.serve_forever, daemon=True).start()
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
base = f"http://127.0.0.1:{port}"

def token(scope):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=")
    unsigned = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode({
        "iss": "ridge-local", "sub": "transport-lab", "aud": ["ridge-orders"], "tenant_id": "tenant-a",
        "scope": scope, "exp": int(time.time()) + 600})
    return (unsigned + b"." + base64.urlsafe_b64encode(hmac.new(
        b"local-training-key-32-bytes-minimum-never-deploy", unsigned, hashlib.sha256).digest()).rstrip(b"=")).decode()

read_token, ops_token = token("orders.read"), token("ops")

def call(path, bearer=read_token):
    request = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + bearer,
        "X-Request-Id": "transport-lab"})
    try:
        response = urllib.request.urlopen(request, timeout=5)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        return response.status, json.load(response)

def sample(name):
    raw = subprocess.run(["/usr/sbin/lsof", "-nP", "-a", "-p", str(process.pid), "-F", "ftnT"],
                         capture_output=True, text=True, timeout=5)
    assert raw.returncode == 0, raw.stderr
    (args.output / (name + "-lsof.txt")).write_text(raw.stdout)
    entries = []
    for line in raw.stdout.splitlines():
        if line.startswith("f"):
            entries.append({"fd": line[1:]})
        elif entries and line.startswith(("t", "n", "T")):
            entries[-1].setdefault(line[0], []).append(line[1:])
    descriptors = [entry for entry in entries if entry["fd"].isdigit()]
    tcp = [entry for entry in descriptors if entry.get("t", [""])[0] in ("IPv4", "IPv6")]
    metrics = {}
    for metric in ("ridge.shipping.available.permits", "jvm.threads.live", "jvm.memory.used"):
        status, body = call("/actuator/metrics/" + metric, ops_token)
        assert status == 200
        metrics[metric] = body["measurements"]
    with guard:
        observed = dict(state)
    return {"name": name, "numeric_open_fds": len(descriptors), "internet_socket_fds": len(tcp),
        "socket_entries": tcp, "peer": observed, "metrics": metrics, "time": time.time()}

def phase(name, mode, seconds=2):
    started_at = time.time()
    with guard:
        state["mode"] = mode
    deadline = time.monotonic() + seconds
    def worker(index):
        counts = {}
        while time.monotonic() < deadline:
            status, body = call("/api/orders/1001/shipping")
            assert status in (200, 502, 503, 504), (status, body)
            if status == 200:
                assert body.get("cents") == 499, body
            if status != 200:
                assert body["code"] == {502: "upstream_failure", 503: "unavailable", 504: "upstream_timeout"}[status]
            counts[str(status)] = counts.get(str(status), 0) + 1
            time.sleep(0.01)
        return counts
    with ThreadPoolExecutor(max_workers=16) as pool:
        batches = list(pool.map(worker, range(16)))
    counts = {str(code): sum(batch.get(str(code), 0) for batch in batches) for code in (200, 502, 503, 504)}
    return {"name": name, "mode": mode, "seconds": seconds, "workers": 16, "statuses": counts,
            "started_at": started_at, "ended_at": time.time(),
            "sample": sample(name)}

results = {"passed": False, "artifact_sha256": artifact_hash,
    "runtime": {"os": platform.platform(), "python": platform.python_version(),
                "java": subprocess.run([str(args.java_home / "bin/java"), "-version"],
                    capture_output=True, text=True, check=True).stderr},
    "post_drain_budget": {"additional_numeric_fds": 12, "additional_internet_socket_fds": 12},
    "peer_protocol": args.peer_protocol,
    "phases": [], "limits": "Three short same-host cycles. FD samples are snapshots, not peak bounds or leak proof. "
    "Abortive close is an intervention, not attribution of historical 502s. Peer backlog=128."}
environment = {key: value for key, value in os.environ.items()
               if key in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL")}
def persist():
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    (args.output / "peer-events.json").write_text(json.dumps(events, indent=2) + "\n")

def check_settled(observed):
    assert observed["peer"]["active"] == 0
    assert observed["metrics"]["ridge.shipping.available.permits"][0]["value"] == 8
    assert observed["numeric_open_fds"] <= results["baseline"]["numeric_open_fds"] + 12
    assert observed["internet_socket_fds"] <= results["baseline"]["internet_socket_fds"] + 12

def check_recovery(observed):
    counts = observed["statuses"]
    assert counts["200"] / max(1, sum(counts.values())) >= 0.5
    assert counts["200"] / max(1, counts["200"] + counts["502"] + counts["504"]) >= 0.95
persist()
try:
    with (args.output / "application.log").open("w") as log:
        process = subprocess.Popen([str(args.java_home / "bin/java"), "-jar", str(args.jar.resolve()),
            "--spring.config.location=classpath:/application.properties", f"--server.port={port}",
            "--spring.datasource.url=jdbc:h2:mem:transportlab;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            f"--ridge.shipping.url=http://127.0.0.1:{peer.server_port}",
            "--ridge.shipping.timeout-ms=250", "--logging.level.press.tenoridge.orders=DEBUG"],
            env=environment, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 30
            while True:
                try:
                    assert call("/actuator/health")[0] == 200
                    break
                except (OSError, AssertionError):
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Application did not become ready")
                    time.sleep(0.1)
            results["baseline"] = sample("baseline")
            for cycle in range(1, 4):
                for mode in ("fast", "slow", "fast"):
                    name = f"cycle-{cycle}-{len(results['phases']) + 1}-{mode}"
                    observed = phase(name, mode)
                    results["phases"].append(observed)
                    if mode == "slow":
                        assert observed["statuses"]["504"] > 0
                        time.sleep(1.4)
                    else:
                        check_recovery(observed)
                    persist()
                time.sleep(1.4)
                results.setdefault("settled", []).append(sample(f"settled-{cycle}"))
                check_settled(results["settled"][-1])
            reset = phase("deliberate-reset", "reset", 1)
            assert reset["statuses"]["502"] > 0
            results["phases"].append(reset)
            with guard:
                state["mode"] = "fast"
            time.sleep(1.4)
            recovery = phase("final-recovery", "fast")
            check_recovery(recovery)
            results["phases"].append(recovery)
            time.sleep(1.4)
            results["final"] = sample("final")
            check_settled(results["final"])
            results["passed"] = True
            persist()
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
finally:
    peer.shutdown()
    peer.server_close()
print(json.dumps({"passed": results["passed"], "phases": len(results["phases"]),
                  "output": str(args.output)}, indent=2))
