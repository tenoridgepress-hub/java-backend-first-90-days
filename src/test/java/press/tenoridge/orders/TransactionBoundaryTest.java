package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionBoundaryTest {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class LabConfiguration {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:proxy_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate database(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean Probe probe(JdbcTemplate database) { return new Probe(database); }
    }

    public static class Probe {
        private final JdbcTemplate database;
        public Probe(JdbcTemplate database) { this.database = database; }
        public void viaSelf() { writeThenFail(); }
        @Transactional public void writeThenFail() {
            database.update("INSERT INTO probe VALUES (1)");
            throw new IllegalStateException("after insert");
        }
        @Transactional public boolean active() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Transactional public void checkedDefault() throws IOException {
            database.update("INSERT INTO probe VALUES (2)");
            throw new IOException("checked failure");
        }
        @Transactional(rollbackFor = IOException.class)
        public void checkedRollback() throws IOException {
            database.update("INSERT INTO probe VALUES (3)");
            throw new IOException("checked failure");
        }
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext(LabConfiguration.class);
        context.getBean(JdbcTemplate.class).execute("CREATE TABLE probe (id INT PRIMARY KEY)");
        return context;
    }

    @Test void proxyEntryRollsBackWhileSelfInvocationCommits() {
        try (var context = context()) {
            var probe = context.getBean(Probe.class);
            var database = context.getBean(JdbcTemplate.class);
            assertTrue(AopUtils.isAopProxy(probe));
            assertTrue(probe.active());
            assertThrows(IllegalStateException.class, probe::writeThenFail);
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM probe", Integer.class));
            assertThrows(IllegalStateException.class, probe::viaSelf);
            assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM probe", Integer.class));
            assertFalse(new Probe(database).active());
            System.out.println("PROXY external=rollback self=committed constructed=inactive");
        }
    }

    @Test void checkedExceptionNeedsAnExplicitRollbackRuleInThisConfiguration() {
        try (var context = context()) {
            var probe = context.getBean(Probe.class);
            var database = context.getBean(JdbcTemplate.class);
            assertThrows(IOException.class, probe::checkedDefault);
            assertThrows(IOException.class, probe::checkedRollback);
            assertEquals(java.util.List.of(2), database.queryForList("SELECT id FROM probe", Integer.class));
            System.out.println("CHECKED default=committed rollbackFor=rolled-back");
        }
    }

    @Test void catchingParticipatingFailureDoesNotClearRollbackOnly() {
        try (var context = context()) {
            var probe = context.getBean(Probe.class);
            var database = context.getBean(JdbcTemplate.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThrows(UnexpectedRollbackException.class, () -> transaction.executeWithoutResult(status -> {
                assertThrows(IllegalStateException.class, probe::writeThenFail);
                database.update("INSERT INTO probe VALUES (4)");
            }));
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM probe", Integer.class));
            System.out.println("REQUIRED caught-inner=rollback-only outer=UnexpectedRollbackException rows=0");
        }
    }
}
