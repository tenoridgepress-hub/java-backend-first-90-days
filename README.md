# Ridge Orders 0.6.1-rc.1 source candidate

Companion to The Java Backend Developer’s First 90 Days by Elliot R. Westford, Tenoridge Press. This is a local candidate for proposed tag v0.6.1-rc.1, awaiting explicit new-release approval. The published v0.6.0-production07 baseline remains preserved. No future release URL is asserted.

## Build and run

Install JDK 21 and Python 3. First builds need Maven repository access; the wrapper downloads the pinned Maven distribution. From the extracted directory, verify FILE-SHA256SUMS.txt with `shasum -a 256 -c FILE-SHA256SUMS.txt` on macOS or `sha256sum -c FILE-SHA256SUMS.txt` on Linux. Check the ZIP against its separately supplied SHA256SUMS.txt before extraction.

Run `python3 tools/reader_lab.py verify`, then `python3 tools/reader_lab.py serve`. Leave that terminal running. In a second terminal, run `python3 tools/reader_lab.py token --scope orders.read` and use the printed token in an Authorization: Bearer header for a GET to http://127.0.0.1:8080/api/orders/1001. Health at http://127.0.0.1:8080/actuator/health is public; an order read without a token returns 401. Use `serve --port 18080` if the default port is occupied.

The launcher removes inherited Spring/Java overrides, selects target/ridge-orders-0.6.1-rc.1.jar and pins disposable H2 state. State disappears on exit. Tests delete fictional rows. Stop only the service you started. The local signing key is public training material. Windows and Docker execution remain unverified.

## Reader route and boundaries

Read PRODUCTION12-READER-ROUTE.md, SETUP.md and EXERCISES.md. The mandatory assessments use supplied code/tests plus the book’s self-contained prompts and rubric. No private starter, mutation patch, trial packet or older JAR is required. README-TECHNICAL-HISTORY.md and RELEASE-LAB.md retain older optional artifact-dependent experiments; do not treat their author-layout paths or old version commands as prerequisites.

Implemented: tenant-scoped access; issuer/audience/tenant validation; common API errors; locking and request/event uniqueness; atomic reservation/outbox recording; manual local delivery with retries/quarantine and a local idempotent consumer; migrations; cursor pagination; bounded shipping deadline/body/admission; quote JSON and UTF-8 validation; outcome metrics; request IDs.

Absent: automatic polling/broker transport, production identity provider, distributed tracing/export and deployment platform. Missing JWT exp remains accepted by the intentional training policy. Fractional reservation numbers are rejected; reservation numeric strings retain the existing converter policy.

Run `python3 tools/shipping_stub.py` for the default loopback peer. Use `--body-delay-ms 1200` to reproduce slow bodies. Shipping accepts required nonnegative integer cents and optional uppercase three-letter currency; malformed representations return 502 and do not count as success. The exact encoding/schema and metrics rules are in PRODUCTION12-READER-ROUTE.md. Use `tools/load_reads_safe.py` for workload exercises; the historical tool is retained for audit only.

## Evidence and license

Coordinator Java 21 tests: 109 H2 and 104 PostgreSQL-selected cases pass. The selections overlap; database-independent shipping tests are included in the PostgreSQL selection. Exact run evidence belongs to the edition checkpoint, not this source-only archive. AI simulations do not establish human reading, device compatibility or physical print quality.

Original companion software, SQL, tests, tools, configuration and documentation use MIT; see LICENSE. Tenoridge Press is the individual publisher’s unregistered independent brand. The book, diagrams, cover and branding are outside the software grant. Maven Wrapper retains Apache 2.0 and upstream notices in THIRD-PARTY-NOTICES.md and licenses/. This ZIP excludes binaries, caches, downloaded dependencies, book files and private author evidence. Binary redistribution requires separate approval/review.
