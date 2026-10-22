# Ridge Orders companion 0.6.0

Companion to *The Java Backend Developer’s First 90 Days* by Elliot R. Westford, Tenoridge Press. Production-07 source package candidate, 22 October 2026. Application version remains 0.6.0; packaging does not change Java source, tests, tools or migrations.

## Start here

Install a JDK 21 and Python 3. Use a POSIX shell for the commands below. Java 25 is also covered by retained verification evidence. A first build needs Maven repository access; offline mode works only after the wrapper and dependencies are cached.

From this directory:

```sh
java -version
./mvnw -version
python3 tools/reader_lab.py verify
python3 tools/reader_lab.py serve
```

Leave that terminal running. Open another terminal in the same directory:

```sh
READ=$(python3 tools/reader_lab.py token --scope orders.read)
curl --max-time 5 -i http://127.0.0.1:8080/actuator/health
curl --max-time 5 -i -H "Authorization: Bearer $READ" http://127.0.0.1:8080/api/orders/1001
```

Expect a public 200 health response and a protected 200 order response. Without the bearer header, the protected read returns 401. Stop only the process you started. See SETUP.md for detailed fixtures and troubleshooting.

The reader launcher clears inherited Spring/Java configuration overrides and pins an in-memory H2 database. State disappears on process exit. Tests delete training rows. Use fictional data and disposable databases; never aim destructive labs at shared state. The sample signing key is public training material.

## What this service teaches

The project uses Java, Spring Boot, explicit JDBC SQL, Flyway, JUnit, H2 and PostgreSQL. It implements tenant-scoped orders, durable reservation identity, atomic outbox recording, manual delivery to a local idempotent consumer, a complete-body shipping deadline, response byte limits and local admission limits.

Delivery does not run automatically. No broker, production identity provider or production deployment service is supplied. An H2 result does not establish PostgreSQL behavior. Docker and the PostgreSQL 17 compose option are supplied examples whose container execution was not reverified in Production-06.

## Exercises and historical labs

Read EXERCISES.md and TRANSFER-EXERCISES.md before consulting worked answers in the book. The ordinary quick start needs no Docker or PostgreSQL installation. Advanced migration/recovery labs require disposable PostgreSQL and installed JDK/database tools.

README-TECHNICAL-HISTORY.md retains the prior project README and explains historical laboratory entry points. Some advanced release rehearsals require preserved older JARs and evidence from the complete edition; the small source archive intentionally excludes those binaries. It does not claim to be a standalone archive of every historical experiment.

## Package identity and support

The named source archive is `ridge-orders-0.6.0-production07-source.zip`. Verify it against the separately supplied release SHA256SUMS.txt before extracting. Inside, run `shasum -a 256 -c FILE-SHA256SUMS.txt` (macOS) or `sha256sum -c FILE-SHA256SUMS.txt` (Linux). A digest proves byte correspondence, not software safety or publisher authenticity.

The repository README is the companion landing page. Use its Releases page for the versioned source ZIP and checksums; preserve published asset bytes and use a new version for corrections. Report setup issues through the repository’s Issues page. No response-time or email-monitoring commitment is made.

## License scope

LICENSE contains the MIT grant for the original companion application, tests, SQL, tools, configuration and companion documentation. The original companion files are owned by the individual publishing as Tenoridge Press. Tenoridge Press is that individual’s unregistered independent publishing brand, not a separate legal entity; no transfer of ownership to a company is claimed. Book text, book diagrams, covers and branding rights are outside that grant. Maven Wrapper scripts retain Apache 2.0 and their upstream attribution; see THIRD-PARTY-NOTICES.md and licenses/.

The source archive excludes downloaded dependency JARs, Java runtimes, Maven distributions and packaged application JARs. Recipients download dependencies under their respective terms. Binary redistribution requires a separate review.
