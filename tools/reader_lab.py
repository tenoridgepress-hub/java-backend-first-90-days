import argparse
import os
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description="Run the supplied lab against fresh in-memory H2 with inherited overrides removed")
parser.add_argument("action", choices=("verify", "serve", "token"))
parser.add_argument("--port", type=int, default=8080)
parser.add_argument("--tests")
parser.add_argument("--offline", action="store_true")
parser.add_argument("--scope", default="orders.read")
parser.add_argument("--shipping-url", default="http://127.0.0.1:8091")
parser.add_argument("--shipping-timeout-ms", type=int, default=250)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
environment = {key: value for key, value in os.environ.items()
               if key in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME")}
properties = {
    "spring.config.location": "classpath:/application.properties",
    "spring.datasource.url": "jdbc:h2:mem:ridge;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username": "sa",
    "spring.datasource.password": "",
}
if args.action == "verify":
    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    command = [str(wrapper), *(["-o"] if args.offline else []),
               *(["test", "-Dtest=" + args.tests] if args.tests else ["clean", "verify"]),
               *("-D" + key + "=" + value for key, value in properties.items())]
elif args.action == "token":
    raise SystemExit(subprocess.call([sys.executable, str(root / "tools/mint_token.py"), args.scope],
                                    cwd=root, env=environment))
else:
    if not 1 <= args.port <= 65535:
        parser.error("port must be in 1..65535")
    java = str(Path(environment["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in environment else "java"
    command = [java, "-jar", str(root / "target/ridge-orders-0.6.0.jar"),
               *("--" + key + "=" + value for key, value in properties.items()), "--server.port=" + str(args.port),
               "--ridge.shipping.url=" + args.shipping_url, "--ridge.shipping.timeout-ms=" + str(args.shipping_timeout_ms)]
print("Reader lab: fresh H2 only; state is lost on exit; inherited Spring/Java option overrides removed.", flush=True)
raise SystemExit(subprocess.call(command, cwd=root, env=environment))
