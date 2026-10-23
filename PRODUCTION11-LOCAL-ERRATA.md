# Production-11 local correction candidate

Historical Production-11 record. PRODUCTION12-READER-ROUTE.md supersedes the shipping limitation and current version below. Retained as correction history, not current reader instructions.

This private source candidate derives from public v0.6.0-production07. The public repository, tag and release assets remain unchanged. Verify the public baseline against its original hashes before applying any changes; this local directory has a separate regenerated FILE-SHA256SUMS.txt.

Corrections:

- application.properties disables float-to-int JSON coercion. OrderApiTest adds the durable-state regression for 2.9, 2.0 and 2e0. Integer input still succeeds. Numeric strings are not addressed by this change.
- tools/load_reads_safe.py is the corrected workload entry point. It disables proxies and rejects all HTTP redirects. tools/load_reads.py remains the exact public baseline for audit; do not run it for the book's workload. The book prints the transformation so a public-release reader can create the safe tool without this private bundle.
- The required assessments and complete rubric now appear in the book. References in older EXERCISES.md or TRANSFER-EXERCISES.md to a private trial packet, author mutation patch or absent historical binaries are optional historical routes, not prerequisites to the source-only milestones. No blind human trial is claimed.

Known limitations retained: shipping forwards an unvalidated peer body and can advertise invalid JSON as application/json; success counters do not validate quote syntax/schema. Expiration is checked when present but an exp claim is not mandatory. Historical old-binary upgrade/rollback requires additional unavailable public artifacts. These limits are documented in the book and production checkpoint. This source is training material, not a production certification.

Use author Elliot R. Westford and imprint Tenoridge Press, an unregistered independent publishing brand. Original MIT and upstream Apache notices remain in force. No new public release, KDP upload, publication or purchase is authorized by this file.
