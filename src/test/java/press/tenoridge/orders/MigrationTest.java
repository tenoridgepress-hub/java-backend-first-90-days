package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationTest {
    @Test void historicalRowsGainTenantAndOldWriterIsRejectedAfterCutover() {
        String url = System.getProperty("ridge.test.migration-url", "jdbc:h2:mem:migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String username = System.getProperty("ridge.test.migration-user", "sa");
        String password = System.getProperty("ridge.test.migration-password", "");
        Flyway.configure().dataSource(url, username, password).target("1").load().migrate();
        var database = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        database.update("INSERT INTO reservation (id, order_id, event_id, quantity) VALUES ('before',1001,'evt-before',2)");
        Flyway.configure().dataSource(url, username, password).load().migrate();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
            database.update("INSERT INTO reservation (id, order_id, event_id, quantity) VALUES ('old',1002,'evt-old',3)"));
        database.update("INSERT INTO reservation (tenant_id, id, order_id, event_id, quantity) VALUES ('tenant-a','after',1002,'evt-after',3)");
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM reservation WHERE created_at IS NOT NULL", Integer.class));
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM reservation WHERE tenant_id='tenant-a'", Integer.class));
    }
}
