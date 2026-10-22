package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyConstraintTest {
    @Test void duplicatesMustBeReconciledBeforeConstraintCanBeAdded() {
        var database = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:legacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        database.execute("CREATE TABLE legacy_reservation (id VARCHAR(36) PRIMARY KEY, order_id BIGINT NOT NULL, event_id VARCHAR(64) NOT NULL)");
        database.update("INSERT INTO legacy_reservation VALUES ('first',1001,'evt-a'),('retry',1001,'evt-a')");
        assertEquals(1, database.queryForObject(
            "SELECT COUNT(*) FROM (SELECT order_id FROM legacy_reservation GROUP BY order_id HAVING COUNT(*) > 1) duplicates", Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> database.execute(
            "ALTER TABLE legacy_reservation ADD CONSTRAINT legacy_order_unique UNIQUE (order_id)"));
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM legacy_reservation", Integer.class));
        database.execute("CREATE TABLE legacy_reconciliation_audit (removed_id VARCHAR(36), retained_id VARCHAR(36), reason VARCHAR(200))");
        database.update("INSERT INTO legacy_reconciliation_audit VALUES ('retry','first','Fictional lab decision: identical delivery; no external fulfilment')");
        database.update("DELETE FROM legacy_reservation WHERE id = 'retry'");
        database.execute("ALTER TABLE legacy_reservation ADD CONSTRAINT legacy_order_unique UNIQUE (order_id)");
        database.execute("ALTER TABLE legacy_reservation ADD CONSTRAINT legacy_event_unique UNIQUE (event_id)");
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
            "INSERT INTO legacy_reservation VALUES ('again',1001,'evt-b')"));
        assertThrows(DataIntegrityViolationException.class, () -> database.update(
            "INSERT INTO legacy_reservation VALUES ('other-order',1002,'evt-a')"));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM legacy_reconciliation_audit", Integer.class));
    }
}
