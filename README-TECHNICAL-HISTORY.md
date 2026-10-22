# Ridge Orders 0.6.0 — Production-04

Java 21, Spring Boot 4.1.1, JDBC, Flyway, JUnit, H2 and PostgreSQL. Training only: default loopback binding, public local signing key, fictional seeded orders. Use a disposable database for tests: cleanup deletes reservations and outbox rows.

```sh
python3 tools/reader_lab.py verify
python3 tools/reader_lab.py serve
```

In another terminal:

```sh
READ=$(python3 tools/reader_lab.py token --scope orders.read)
WRITE=$(python3 tools/reader_lab.py token --scope orders.write)
curl --max-time 5 -i http://127.0.0.1:8080/actuator/health
curl --max-time 5 -i -H "Authorization: Bearer $READ" http://127.0.0.1:8080/api/orders/1001
curl --max-time 5 -i -H "Authorization: Bearer $READ" 'http://127.0.0.1:8080/api/orders?limit=1'
curl --max-time 5 -i -H "Authorization: Bearer $WRITE" -H 'Content-Type: application/json' \
  -d '{"eventId":"reader-1","quantity":2}' http://127.0.0.1:8080/api/orders/1001/reservations
```

Repeat the final command: the reservation identity remains the same and the outbox is not duplicated. A changed event for that order conflicts. Order 1002 has quantity 3.

Implemented: issuer/audience/tenant validation and tenant-scoped order access; common error codes; parent-row locking and tenant-scoped request/event uniqueness; atomic reservation/outbox recording; manually invoked dispatcher with two-call admission, retries/quarantine and an independent local idempotent consumer; versioned migrations including a V6 maintenance cutover; cursor pagination; full-body shipping deadline with best-effort cancellation; response byte cap; shipping admission/permit gauge; outcome counters/timers; request IDs; graceful shutdown configuration.

Absent: automatic polling worker/broker transport, production identity provider, distributed tracing/export pipeline and deployment platform. Dispatcher methods are exercised by the integration lab; normal startup does not poll. Consumer effects are local SQL only. V6 requires stopping old writers and dispatchers before migration; it rejects missing totals, unreconciled legacy effects, unowned/mismatched receipts and inconsistent outbox payloads before DDL. Consumer totals initialize safely for newly introduced tenants. Old writers omit outbox rows; pre-tenancy binaries must not serve tenant traffic. V5 assigns the fictional historical orders to tenant-a and removes the default for future orders; this is not a production ownership-discovery migration.

Run `python3 tools/shipping_stub.py` on port 8091 for quotes. `--body-delay-ms 1200` reproduces a slow body; `--delay-ms 1200` delays headers. The default application deadline is 250 ms. End-to-end requests retain an outer caller timeout.

Current test results are in `../evidence/production04/verification-results.json`. Production-02/03 evidence retains its historical artifact association. Container files have not been validated in this continuation. The release rehearsal supplied in RELEASE-LAB.md is historical 0.3.0 evidence. The new current_upgrade_lab.py supplies a distinct V5-to-V6 maintenance upgrade/restore experiment.

New isolated labs (run from this directory; supply installed PostgreSQL/JDK locations):

```sh
python3 tools/postgres_recovery_lab.py --pg-bin /path/to/postgresql/bin --output ./lab-evidence
python3 tools/profile_workload_lab.py --java-home /path/to/jdk21 \
  --jar target/ridge-orders-0.6.0.jar --output ./profile-evidence
```

The PostgreSQL lab creates a temporary cluster and retains a custom-format dump plus exact logs. The workload starts its own loopback application and peer, runs 18 seconds across healthy/fault/recovery phases, records JFR and saves metrics/thread observations. Neither lab is a production capacity or disaster-recovery certification.

See SETUP.md, EXERCISES.md, TRANSFER-EXERCISES.md and RELEASE-LAB.md. The editable manuscript and listing manifest are one directory above this companion. The reader launcher pins a disposable in-memory H2 destination and removes inherited Spring/Java option overrides; its serve action loses state on exit.

## Recovery and transport lab entry points

Run with fresh output directories. Substitute installed tool paths:

```sh
python3 tools/restart_outbox_lab.py --java-home /path/to/jdk21 \
  --postgres-bin /path/to/postgresql/bin --jar target/ridge-orders-0.6.0.jar --output ./restart-evidence
python3 tools/transport_resource_lab.py --java-home /path/to/jdk21 \
  --jar target/ridge-orders-0.6.0.jar --output ./transport-http10
python3 tools/transport_resource_lab.py --java-home /path/to/jdk21 \
  --jar target/ridge-orders-0.6.0.jar --peer-protocol HTTP/1.1 --output ./transport-http11
```

The restart runner kills only its own child JVMs at three recorded boundaries while a disposable PostgreSQL server retains state. It compares stable reservation identity, receipts, totals and acknowledgement state after restart; it also executes labs/tenant-diagnostics.sql. The transport runner needs macOS /usr/sbin/lsof. It measures three finite fault/recovery cycles, declared post-drain descriptor/socket budgets and useful recovery, then deliberately resets peer sockets. It retains raw observations. These are JVM restart and finite resource-recovery checks, not database/host disaster-recovery or production capacity certification.

Historical transport observations are in ../evidence/production03/transport-final-http10 and transport-final-http11 and identify the 0.5.0 JAR. Their results must not be relabeled as new 0.6.0 observations. Current rerun locations are named in the Production-04 checkpoint. See it for unresolved reset attribution and publication gates.

## Production-04 teaching and upgrade labs

Run `python3 tools/reader_lab.py verify --tests TransactionBoundaryTest,MigrationFailureTest` for proxy/self-call, checked rollback, rollback-only completion, failure after V6 DDL, PostgreSQL rollback/retry and H2 backup/restore/retry. Without migration datasource properties, the migration test runs on H2.

From the edition root, after packaging the candidate:

```sh
python3 companion/tools/current_upgrade_lab.py --java-home /path/to/jdk21 --pg-bin /path/to/postgresql/bin --previous-jar ridge-orders-0.4.0.jar --candidate-jar ridge-orders-0.6.0.jar --output ./current-upgrade-evidence
```

The script uses temporary loopback PostgreSQL databases. It stops the old application before V6, combines 200 SQL history fixtures with two real HTTP acceptances, invokes the manual local consumer, and restores both V5 and V6 backups into empty databases. The V5 restore deliberately demonstrates loss of post-backup accepted work. The V6 restore compares complete business rows and both API identities. It does not demonstrate zero-downtime migration or remote delivery.

MaintenanceDeliveryLab is an explicit local maintenance entry point with a row cap. It is not an automatic poller or bounded external delivery adapter. New assessments and worked answers are in Chapters 2, 4, 9, 13, 15, 17, 18 and 20.
