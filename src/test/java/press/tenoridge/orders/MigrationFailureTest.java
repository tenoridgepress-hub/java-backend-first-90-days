package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationFailureTest {
    @Test void failureAfterV6DdlExposesEngineRecoveryDifference() throws Exception {
        String url = System.getProperty("ridge.test.migration-url",
            "jdbc:h2:mem:ddl_failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String user = System.getProperty("ridge.test.migration-user", "sa");
        String password = System.getProperty("ridge.test.migration-password", "");
        String schema = "after_ddl_failure";
        Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
            .target("5").load().migrate();
        var source = new DriverManagerDataSource(url,user,password);
        try (var connection = source.getConnection()) {
            connection.setSchema(schema);
            var database = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            database.update("INSERT INTO reservation (id,order_id,event_id,quantity) VALUES ('ddl',1001,'ddl-event',2)");
            database.update("INSERT INTO reservation_outbox (event_id,reservation_id,order_id,quantity) VALUES ('ddl-event','ddl',1001,2)");
            database.update("INSERT INTO consumer_inbox (event_id,reservation_id,order_id,quantity) VALUES ('ddl-event','ddl',1001,2)");
            database.update("UPDATE fulfillment_total SET quantity=2");
            boolean postgres = connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
            var backup = java.nio.file.Files.createTempFile("ridge-v5-", ".sql");
            if (!postgres) database.execute("SCRIPT TO '" + backup.toString().replace("'", "''") + "'");
            var reached = new java.util.concurrent.atomic.AtomicBoolean();
            Callback failure = new Callback() {
                public boolean supports(Event event, Context context) {
                    return event == Event.AFTER_EACH_MIGRATE
                        && context.getMigrationInfo().getVersion().toString().equals("6");
                }
                public boolean canHandleInTransaction(Event event, Context context) { return true; }
                public String getCallbackName() { return "fail-after-v6-ddl"; }
                public void handle(Event event, Context context) {
                    reached.set(true);
                    throw new FlywayException("Injected failure after V6 structural changes");
                }
            };
            assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(url,user,password)
                .schemas(schema).defaultSchema(schema).callbacks(failure).load().migrate());
            assertTrue(reached.get());
            if (postgres) {
                assertThrows(org.springframework.jdbc.BadSqlGrammarException.class,
                    () -> database.queryForList("SELECT tenant_id FROM reservation"));
                assertEquals(2L,database.queryForObject("SELECT quantity FROM fulfillment_total WHERE id=1",Long.class));
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
                assertEquals("tenant-a",database.queryForObject("SELECT tenant_id FROM reservation",String.class));
            } else {
                assertEquals("tenant-a",database.queryForObject("SELECT tenant_id FROM reservation",String.class));
                assertEquals(2L,database.queryForObject("SELECT quantity FROM fulfillment_total_legacy",Long.class));
                assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(url,user,password)
                    .schemas(schema).defaultSchema(schema).load().migrate());
                String restoredUrl = "jdbc:h2:mem:restored_" + java.util.UUID.randomUUID()
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
                var restoredSource = new DriverManagerDataSource(restoredUrl,user,password);
                var restored = new JdbcTemplate(restoredSource);
                restored.execute("RUNSCRIPT FROM '" + backup.toString().replace("'", "''") + "'");
                try (var restoredConnection = restoredSource.getConnection()) {
                    restoredConnection.setSchema(schema);
                    var restoredDatabase = new JdbcTemplate(
                        new org.springframework.jdbc.datasource.SingleConnectionDataSource(restoredConnection,true));
                    assertEquals(2L,restoredDatabase.queryForObject("SELECT quantity FROM fulfillment_total WHERE id=1",Long.class));
                    Flyway.configure().dataSource(restoredUrl,user,password).schemas(schema).defaultSchema(schema).load().migrate();
                    assertEquals("tenant-a",restoredDatabase.queryForObject("SELECT tenant_id FROM reservation",String.class));
                    assertEquals(2L,restoredDatabase.queryForObject("SELECT quantity FROM fulfillment_total WHERE tenant_id='tenant-a'",Long.class));
                    assertEquals("ddl",restoredDatabase.queryForObject("SELECT reservation_id FROM consumer_inbox",String.class));
                }
            }
            java.nio.file.Files.delete(backup);
            System.out.println("DDL_FAILURE engine=" + connection.getMetaData().getDatabaseProductName()
                + " after-v6=true recovery=" + (postgres ? "transaction-rollback-and-retry" : "backup-restore-and-retry"));
        }
    }
}
