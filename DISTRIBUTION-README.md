# Ridge Orders source distribution

Proposed version 0.6.1-rc.1 and tag v0.6.1-rc.1. Local review candidate; no new public release has been made. The existing v0.6.0-production07 release remains the public baseline.

Start with README.md and PRODUCTION12-READER-ROUTE.md. The source-only archive ridge-orders-0.6.1-rc.1-source.zip contains application code, tests, migrations, tools, documentation and original/upstream license notices. It excludes executable JARs, dependency downloads, runtime installations, author evidence and book files.

Verify the ZIP using separately supplied SHA256SUMS.txt. After extraction, verify FILE-SHA256SUMS.txt before building. Hashes establish byte correspondence, not safety or publisher authenticity. Install JDK 21/Python 3, then use `python3 tools/reader_lab.py verify` and `python3 tools/reader_lab.py serve`. A first build requires repository access. The launcher uses disposable in-memory H2 and the versioned candidate JAR it builds locally.

The mandatory reader assessments are self-contained in the book and supplied tests. Optional older binary/mutation experiments remain artifact-dependent and are not required. Use tools/load_reads_safe.py for the workload. No automatic worker, broker or production identity/deployment service is supplied.

MIT covers original companion files, not book text, diagrams, cover or branding. Maven Wrapper retains Apache 2.0; see LICENSE, THIRD-PARTY-NOTICES.md and licenses/. Tenoridge Press is an unregistered independent publishing brand of the individual publisher. No separate legal entity or trademark clearance is claimed.

A future public release must be separately approved, use a new tag/commit and preserve old assets. Its named ZIP must be anonymously downloaded and compared before the book advertises a live new-release URL.
