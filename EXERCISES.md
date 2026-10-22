# Executable exercise index

Run from this directory against disposable state. Full explanation and solutions are in the edition-level manuscript.

| Task | Run or starting artifact | Acceptance |
|---|---|---|
| baseline | `python3 tools/reader_lab.py verify` and README requests | health/authentication/authorization distinguished |
| replay | `python3 tools/reader_lab.py verify --tests OrderApiTest#replayReturnsSameReservation` | same body/identity; one durable row |
| same-tenant cross-order race | `python3 tools/reader_lab.py verify --tests OrderApiTest#concurrentEventAcrossOrdersHasOneWinner` | one winner; matching durable fields |
| outbox recording | `python3 tools/reader_lab.py verify --tests OrderApiTest#reservationAndOutboxAreAtomicAndReplayDoesNotDuplicateEvent` | one linked event after replay |
| real insert failure | `python3 tools/reader_lab.py verify --tests OrderApiTest#outboxInsertFailureRollsBackNewReservation` | failed second insert leaves both tables unchanged |
| full-body timeout | `python3 tools/reader_lab.py verify --tests ShippingClientTest#timelyHeadersAndDelayedBodyRespectDeadline` | 504 in bounded window; healthy recovery |
| response size | `python3 tools/reader_lab.py verify --tests ShippingClientTest#oversizedBodyReturns502ThenRecovers` | 502 then success |
| overload | `python3 tools/reader_lab.py verify --tests ShippingClientTest#saturatedClientRejectsImmediatelyAndReleasesPermitAfterTimeout` | immediate 503, first 504, then recovery |
| byte accounting | `python3 tools/reader_lab.py verify --tests LimitedBodySubscriberTest` | cap/UTF-8/error behavior |
| schema expansion | `python3 tools/reader_lab.py verify --tests MigrationTest` | historical tenant backfill; old writer rejected after V6; tenant-aware insert succeeds |
| plans | `labs/query-plans.sql` on disposable PostgreSQL | compare scanned rows, not just local timings |
| row locks | `labs/LockTimeoutLab.java` with PostgreSQL JDBC driver | 55P03, then 25P02, then recovery |
| finite workload | `tools/load_reads.py` with RIDGE_READ_TOKEN | all authenticated reads succeed; limits stated |

To challenge the timeout repair, make a separate source copy, apply the edition's `evidence/timeout-mutation.patch` with `patch -p1`, and run the body-delay test. That mutation is expected to fail on Java 21. Do not apply it to your only working solution. It removes the bounded future wait and late-result rejection, recreating reliance on the request timeout alone. Restore the solution copy for ordinary labs.

No intentionally defective starter is shipped as the default application. Prior-edition starters remain in their original folders. This edition's mutation patch and exercises provide an explicit failing experiment without confusing the executable solution with the exercise state.
