import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument("--delay-ms", type=int, default=0)
parser.add_argument("--body-delay-ms", type=int, default=0)
options = parser.parse_args()


class ShippingHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        time.sleep(options.delay_ms / 1000)
        body = json.dumps({"cents": 499, "currency": "USD"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body[:1])
            self.wfile.flush()
            time.sleep(options.body_delay_ms / 1000)
            self.wfile.write(body[1:])
        except (BrokenPipeError, ConnectionResetError):
            pass


ThreadingHTTPServer(("127.0.0.1", 8091), ShippingHandler).serve_forever()
