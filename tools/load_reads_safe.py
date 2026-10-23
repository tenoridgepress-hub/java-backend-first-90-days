import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
import statistics
import time
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description='Finite closed-loop read workload for a local Ridge Orders lab')
parser.add_argument('--base', default='http://127.0.0.1:8080')
parser.add_argument('--workers', type=int, default=8)
parser.add_argument('--per-worker', type=int, default=32)
arguments = parser.parse_args()
destination = urllib.parse.urlparse(arguments.base)
if destination.scheme != 'http' or destination.hostname not in ('127.0.0.1', 'localhost'):
    parser.error('Use a local loopback HTTP lab destination')
if not 1 <= arguments.workers <= 32 or not 1 <= arguments.per_worker <= 1000:
    parser.error('workers must be 1..32 and per-worker must be 1..1000')
bearer = os.environ['RIDGE_READ_TOKEN']

class RejectRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None

opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({}), RejectRedirect())

def request_once():
    request = urllib.request.Request(arguments.base.rstrip('/') + '/api/orders/1001',
        headers={'Authorization': 'Bearer ' + bearer, 'X-Request-Id': 'finite-read-lab'})
    started = time.perf_counter()
    with opener.open(request, timeout=5) as response:
        body = json.load(response)
        if response.status != 200 or body['id'] != 1001:
            raise RuntimeError('Unexpected read result')
    return (time.perf_counter() - started) * 1000

def worker(index):
    return [request_once() for attempt in range(arguments.per_worker)]

for attempt in range(5):
    request_once()
started = time.perf_counter()
with ThreadPoolExecutor(max_workers=arguments.workers) as executor:
    durations = [elapsed for batch in executor.map(worker, range(arguments.workers)) for elapsed in batch]
wall_seconds = time.perf_counter() - started
durations.sort()
result = {'workload': 'finite closed-loop authenticated order reads; five unmeasured warmups',
    'workers': arguments.workers, 'completed': len(durations), 'wall_seconds': round(wall_seconds, 4),
    'requests_per_second': round(len(durations) / wall_seconds, 2),
    'client_elapsed_ms': {'min': round(durations[0], 3), 'p50': round(statistics.median(durations), 3),
        'p95_nearest_rank': round(durations[math.ceil(len(durations) * 0.95) - 1], 3),
        'max': round(durations[-1], 3)},
    'limits': 'Local client and server share a host; no sustained-capacity or production percentile claim'}
print(json.dumps(result, indent=2))
