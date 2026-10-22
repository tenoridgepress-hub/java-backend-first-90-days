# Historical 0.3.0 retained-artifact rehearsal

Production-04 supplies the retained 0.2.0 and 0.3.0 artifacts at the edition root. This historical script does not certify 0.6.0. Do not use pre-tenancy binaries for tenant traffic. The self-contained current-application backup/restore lab is tools/current_upgrade_lab.py, documented in Chapter 9.

```sh
python3 tools/rehearse_release.py \
  --previous-jar ../ridge-orders-0.2.0.jar \
  --candidate-jar ../ridge-orders-0.3.0.jar \
  --evidence release-evidence
```

The script hashes artifacts, creates a private file-based H2 database, launches previous code at schema V2, migrates to V3 with the candidate, then restarts the same previous bytes on V3. It checks reads, denied writes and retained reservation IDs. Candidate shipping is exercised as healthy/slow-headers/recovered. Complete-body delay is covered separately by ShippingClientTest and the edition runtime probe.

The script uses the `java` executable on PATH. Select the intended JDK before execution. It removes inherited Spring/Java option overrides, pins classpath configuration and its temporary H2 destination, and requires a fresh evidence directory. Its temporary database is removed and processes are stopped. An initial passed=false result is retained on ordinary Python failure; passed=true is assigned only after the checks complete.

Limits of the historical run: native H2, not Docker or a rolling deployment; no schema downgrade; no backup restore; no fresh old-code write in the final stage; no universal outbox guarantee under old writers. The candidate-created reservation is replayed after rollback. Old insert-column compatibility is a separate migration test. At that checkpoint the outbox had no dispatcher or legacy backfill.

The current MigrationTest requires rejection of an old reservation writer after V6. This document records the retained 0.2.0/0.3.0 rehearsal, not current-binary rollback compatibility.
