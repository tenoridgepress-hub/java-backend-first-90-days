# Local setup

Use a JDK 21 installation, Python 3 and curl. The Maven wrapper downloads Maven/dependencies if absent; first use requires repository access. Confirm both `java -version` and `./mvnw -version`. The project targets release 21; verification also ran on VM 25.

```sh
python3 tools/reader_lab.py verify
python3 tools/reader_lab.py serve
```

The reader launcher pins fresh H2 and removes inherited Spring/Java option overrides. State belongs to that JVM; restarting clears reservations. Use `python3 tools/reader_lab.py serve --port 8082` if your chosen port is occupied. Direct Maven commands below intentionally accept explicit database destinations and delete test data.

For disposable PostgreSQL, create separate empty API and migration databases. Substitute actual local connection values:

```sh
./mvnw -Dtest=OrderApiTest,MigrationTest,TenantMigrationTest,MigrationFailureTest,TenantDeliveryTest,OutboxDeliveryTest \
  -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:5432/ridge_api \
  -Dspring.datasource.username=ridge -Dspring.datasource.password=local-only \
  -Dridge.test.migration-url=jdbc:postgresql://127.0.0.1:5432/ridge_migration \
  -Dridge.test.migration-user=ridge -Dridge.test.migration-password=local-only test
```

Those are templates, not an assertion that a local server/user exists. The edition-level authoring verifier creates and tears down its own PostgreSQL 14 cluster on the author's macOS machine. Its absolute executable paths are author-environment details, not cross-platform setup instructions.

The compose file optionally describes PostgreSQL 17 on host port 5439. It was not rerun here. Do not mix its database/password with the example above without adapting the values.

Windows translation (not executed in this edition): the Python reader launcher selects mvnw.cmd. Use `curl.exe` where curl is an alias. Direct application launch uses `java -jar target\ridge-orders-0.6.1-rc.1.jar`. Socket permission errors indicate an execution-environment restriction and must be resolved before interpreting HTTP tests.

For local metrics, mint `python3 tools/reader_lab.py token --scope ops` and request `/actuator/metrics`. No Prometheus scrape registry is installed. The known signing key is for these loopback labs only.
