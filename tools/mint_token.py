import base64
import hashlib
import hmac
import json
import os
import sys
import time


def encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=")


secret = os.environ.get("RIDGE_JWT_SECRET", "local-training-key-32-bytes-minimum-never-deploy")
header = encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
payload = encode(json.dumps({"sub": "reader", "iss": "ridge-local", "exp": int(time.time()) + 1800,
                             "aud": ["ridge-orders"], "tenant_id": "tenant-a",
                             "scope": sys.argv[1] if len(sys.argv) > 1 else "orders.read orders.write ops"}).encode())
unsigned = header + b"." + payload
print((unsigned + b"." + encode(hmac.new(secret.encode(), unsigned, hashlib.sha256).digest())).decode())
