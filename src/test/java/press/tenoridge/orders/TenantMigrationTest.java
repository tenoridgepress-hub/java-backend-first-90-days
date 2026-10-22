package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class TenantMigrationTest {
    @Test void eachEnvelopeMismatchIsRefusedBeforeDdl() throws Exception {
        for (String mutation : java.util.List.of("outbox-quantity", "outbox-order", "receipt-quantity")) {
            String schema = "preflight_" + mutation.replace("-", "_");
            migration(schema,"5").migrate();
            try (var source = source(schema)) {
                var database = new JdbcTemplate(source);
                database.update("INSERT INTO reservation (id,order_id,event_id,quantity) VALUES ('mismatch',1001,'event',2)");
                database.update("INSERT INTO reservation_outbox (event_id,reservation_id,order_id,quantity) VALUES ('event','mismatch',1001,2)");
                database.update("INSERT INTO consumer_inbox (event_id,reservation_id,order_id,quantity) VALUES ('event','mismatch',1001,2)");
                database.update("UPDATE fulfillment_total SET quantity=2");
                if (mutation.equals("outbox-quantity")) database.update("UPDATE reservation_outbox SET quantity=3");
                if (mutation.equals("outbox-order")) database.update("UPDATE reservation_outbox SET order_id=1002");
                if (mutation.equals("receipt-quantity")) {
                    database.update("UPDATE consumer_inbox SET quantity=3");
                    database.update("UPDATE fulfillment_total SET quantity=3");
                }
                var before = database.queryForList("SELECT * FROM reservation_outbox");
                assertThrows(org.flywaydb.core.api.FlywayException.class, () -> migration(schema,null).migrate());
                assertThrows(org.springframework.jdbc.BadSqlGrammarException.class,
                    () -> database.queryForList("SELECT tenant_id FROM reservation"));
                assertEquals(before,database.queryForList("SELECT * FROM reservation_outbox"));
            }
        }
        System.out.println("PREFLIGHT outbox-quantity=refused outbox-order=refused receipt-quantity=refused before-ddl=true");
    }

    @Test void populatedInboxAndTotalsBackfillFromAuthoritativeOrderTenant() throws Exception {
        String schema = "tenant_backfill";
        var flyway = migration(schema, "5");
        flyway.migrate();
        try (var source = source(schema)) {
            var database = new JdbcTemplate(source);
            database.update("INSERT INTO customer_order VALUES (2001,'b',3,'tenant-b')");
            database.update("INSERT INTO reservation (id,order_id,event_id,quantity) VALUES ('a',1001,'old-a',2),('b',2001,'old-b',3)");
            database.update("INSERT INTO reservation_outbox (event_id,reservation_id,order_id,quantity) VALUES ('old-a','a',1001,2),('old-b','b',2001,3)");
            database.update("INSERT INTO consumer_inbox (event_id,reservation_id,order_id,quantity) VALUES ('old-a','a',1001,2),('old-b','b',2001,3)");
            database.update("UPDATE fulfillment_total SET quantity=5");
            migration(schema, null).migrate();
            for (String table : java.util.List.of("reservation","reservation_outbox","consumer_inbox")) {
                assertEquals("tenant-b",database.queryForObject("SELECT tenant_id FROM " + table + " WHERE order_id=2001",String.class));
            }
            assertEquals(2L,database.queryForObject("SELECT quantity FROM fulfillment_total WHERE tenant_id='tenant-a'",Long.class));
            assertEquals(3L,database.queryForObject("SELECT quantity FROM fulfillment_total WHERE tenant_id='tenant-b'",Long.class));
            assertEquals(5L,database.queryForObject("SELECT quantity FROM fulfillment_total_legacy",Long.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> database.update(
                "INSERT INTO reservation_outbox (tenant_id,event_id,reservation_id,order_id,quantity) VALUES ('tenant-b','bad','a',1001,2)"));
        }
    }

    @Test void unownedReceiptFailsBeforeSchemaChange() throws Exception {
        String schema = "tenant_orphan";
        migration(schema,"5").migrate();
        try (var source = source(schema)) {
            new JdbcTemplate(source).update("INSERT INTO consumer_inbox (event_id,reservation_id,order_id,quantity) VALUES ('orphan','lost',9999,2)");
            assertThrows(org.flywaydb.core.api.FlywayException.class, () -> migration(schema,null).migrate());
            assertEquals(1,new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM consumer_inbox",Integer.class));
            assertThrows(org.springframework.jdbc.BadSqlGrammarException.class, () ->
                new JdbcTemplate(source).queryForList("SELECT tenant_id FROM consumer_inbox"));
        }
    }

    @Test void missingLegacySingletonStopsMigration() throws Exception {
        String schema = "tenant_missing_total";
        migration(schema,"5").migrate();
        try (var source = source(schema)) {
            var database = new JdbcTemplate(source);
            database.update("DELETE FROM fulfillment_total");
            assertThrows(org.flywaydb.core.api.FlywayException.class, () -> migration(schema,null).migrate());
            assertEquals(0,database.queryForObject("SELECT COUNT(*) FROM fulfillment_total",Integer.class));
        }
    }

    @Test void matchingGlobalSumCannotHideMismatchedReceiptOwnership() throws Exception {
        String schema = "tenant_bad_receipt";
        migration(schema,"5").migrate();
        try (var source = source(schema)) {
            var database = new JdbcTemplate(source);
            database.update("INSERT INTO customer_order VALUES (2001,'b',2,'tenant-b')");
            database.update("INSERT INTO reservation (id,order_id,event_id,quantity) VALUES ('a',1001,'same',2)");
            database.update("INSERT INTO reservation_outbox (event_id,reservation_id,order_id,quantity) VALUES ('same','a',1001,2)");
            database.update("INSERT INTO consumer_inbox (event_id,reservation_id,order_id,quantity) VALUES ('same','a',2001,2)");
            database.update("UPDATE fulfillment_total SET quantity=2");
            assertThrows(org.flywaydb.core.api.FlywayException.class, () -> migration(schema,null).migrate());
            assertEquals(2L,database.queryForObject("SELECT quantity FROM fulfillment_total",Long.class));
        }
    }

    @Test void unreconciledBusinessTotalStopsMigration() throws Exception {
        String schema = "tenant_bad_total";
        migration(schema,"5").migrate();
        try (var source = source(schema)) {
            var database = new JdbcTemplate(source);
            database.update("UPDATE fulfillment_total SET quantity=9");
            assertThrows(org.flywaydb.core.api.FlywayException.class, () -> migration(schema,null).migrate());
            assertEquals(9L,database.queryForObject("SELECT quantity FROM fulfillment_total",Long.class));
        }
    }

    private Flyway migration(String schema, String target) {
        var configuration = Flyway.configure().dataSource(url(), username(), password())
            .schemas(schema).defaultSchema(schema);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private SingleConnectionDataSource source(String schema) throws Exception {
        var source = new DriverManagerDataSource(url(),username(),password());
        var connection = source.getConnection();
        connection.setSchema(schema);
        return new SingleConnectionDataSource(connection,true);
    }

    private String url() {
        return System.getProperty("ridge.test.migration-url","jdbc:h2:mem:tenantmigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }
    private String username() {
        return System.getProperty("ridge.test.migration-user","sa");
    }
    private String password() {
        return System.getProperty("ridge.test.migration-password","");
    }
}
