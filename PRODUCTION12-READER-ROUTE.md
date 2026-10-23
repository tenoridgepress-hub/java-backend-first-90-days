# Production 12 source candidate

Proposed application version and tag: 0.6.1-rc.1 / v0.6.1-rc.1. This is a local source candidate awaiting new-release approval. It is not a public download. The public v0.6.0-production07 release remains the baseline identified in the book.

Start with JDK 21 and Python 3, then run `python3 tools/reader_lab.py verify` and `python3 tools/reader_lab.py serve`. The launcher uses the versioned candidate JAR built locally by verify. Build outputs and downloaded dependencies are excluded from the source ZIP. Source-only release approval does not authorize binary redistribution.

The required exercises use supplied code/tests and the self-contained book prompts and rubric. Ignore older author-layout prerequisites in historical notes. No private packet, starter generator or historical JAR is required for the mandatory route. Historical upgrade exercises remain optional and require their named older artifact.

Reservation fractional numeric representations are rejected by the retained Production-11 setting. Reservation numeric strings retain the existing converter policy; a stricter contract has not been adopted. Missing JWT exp remains intentionally accepted when the token otherwise satisfies the supplied validator policy.

The shipping adapter accepts exactly one JSON object: required nonnegative signed-64-bit integer cents and optional three-uppercase-ASCII-letter currency. It retains cents-only fixtures. Unknown fields, duplicates, trailing values, fractional/quoted cents and malformed UTF-8 are rejected as 502. This is an explicit narrow adapter contract, not an ISO currency lookup. Body byte, parser and total-time limits apply. An absent Content-Type is tolerated for historical peers; a supplied type must be application/json with absent or UTF-8 charset. Content-Encoding must be absent or identity. Validated responses alone count as success; invalid representations count as invalid_response. Timeout, saturation, interruption and transport/status outcomes retain their existing names.

The workload entry point is `tools/load_reads_safe.py`: proxies disabled, redirects rejected. Use it instead of the historical `tools/load_reads.py`. The latter remains solely as a historical artifact.

All AI reader checks are simulations on a shared host with existing tools/caches. Human completion, actual Kindle/device review and physical print proof remain separate gates.
