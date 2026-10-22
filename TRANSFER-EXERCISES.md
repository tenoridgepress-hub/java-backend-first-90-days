# Independent attempts before worked answers

These exercises are author-designed. No uncoached reader completion is claimed. Keep your attempt before opening Chapter 23's transfer solutions. Use the four-dimension rubric in Chapter 22 and reader-trial-kit/ACCEPTANCE-RUBRIC.md. Suggested time boxes are planning aids, not measured reader timings.

## T1 — Diagnose an unfamiliar evidence packet (25 minutes)

This is a fictional packet, not captured production output. A client has a correctly signed tenant-a token containing only orders.write. GET /api/orders/1001 returns 403; POST /api/orders/1001/reservations with eventId transfer-1 and quantity 2 returns 200. The client retries GET twice and receives 403 again. A colleague proposes removing the tenant predicate because the write worked.

Write the earliest responsible boundary, one safe discriminating request, and a review comment on the proposed repair. Then state what the packet cannot establish about a foreign tenant. Do not inspect the worked solution until you have committed your explanation to your notebook.

Acceptance: explain both statuses with the configured policy; avoid an authorization bypass; name a test that could reject your diagnosis. The packet is sufficient for a policy hypothesis, not a claim that the supplied application has a new defect.

## T2 — Implement an observation query (40 minutes)

In a copy of the companion, write a single SQL aggregate that returns four non-null counts: delivered, quarantined, delayed, eligible. Classify delivered rows first, then undelivered quarantine, then undelivered non-quarantined rows by whether next_attempt_at is after CURRENT_TIMESTAMP. Empty input must produce four zeros. Do not change application code.

Build a disposable fixture with one row in each state and a delivered row whose quarantined flag is also true. Your expected counts are 2,1,1,1. Record the exact SQL, engine/version and output, then show the empty-input case. Use a transaction or an isolated table/schema and discard the fixture. Never run cleanup against a shared database. Do not claim your query counts all externally unfinished shipments.

Acceptance: mutually exclusive classification, explicit empty-input handling, five-row fixture, and a sentence describing snapshot/concurrency limits. Chapter 23 supplies the worked reasoning rather than a drop-in query so the implementation remains your work.

## T3 — Review a changed recovery instruction (30 minutes)

A fictional diff replaces “restore the current V6 backup and replay known identities” with “restore the V5 backup and retry every event with a new eventId.” Both instructions start an application whose health endpoint is green.

Write two blocking review comments, a bounded replacement instruction and the evidence you would request. Keep each comment tied to one concrete failure. Then run the supplied current_upgrade_lab.py only if you have its installed prerequisites and a fresh evidence directory. Otherwise use the saved final results as read-only evidence and label that distinction.

Acceptance: identify the lost post-backup acceptance and the changed retry identity; distinguish a local database restore from reversal of external effects; do not invent a universally safe recovery procedure.

## Record help honestly

If another person or assistant supplies the boundary or command selector, retain that help in the attempt. A coached exercise can be useful learning but cannot close the book's uncoached-reader gate. Chapter 23 contains worked assessments for T1–T3.
