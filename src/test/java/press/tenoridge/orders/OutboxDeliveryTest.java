package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Timeout(20)
class OutboxDeliveryTest {
    @Autowired JdbcTemplate database;
    @Autowired OrderService orders;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired IdempotentConsumer consumer;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void reset() {
        for (String table : List.of("consumer_inbox", "reservation_outbox", "reservation")) database.update("DELETE FROM " + table);
        database.update("DELETE FROM fulfillment_total WHERE tenant_id <> 'tenant-a'");
        database.update("UPDATE fulfillment_total SET quantity = 0 WHERE tenant_id = 'tenant-a'");
        orders.reserve("tenant-a", 1001, "delivery-1", 2);
    }

    private int count(String table) {
        return database.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void retryNow() {
        database.update("UPDATE reservation_outbox SET next_attempt_at = CURRENT_TIMESTAMP");
    }

    @Test void recorderFailureRetainsTenantAndEventInDiagnostic() {
        var brokenRecorder = new JdbcTemplate(java.util.Objects.requireNonNull(database.getDataSource())) {
            @Override public int update(String sql, Object... arguments) {
                if (sql.contains("attempts = attempts + 1")) {
                    throw new org.springframework.dao.CannotAcquireLockException("injected recorder failure");
                }
                return super.update(sql, arguments);
            }
        };
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OutboxDispatcher.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var failure = assertThrows(IllegalStateException.class, () ->
                new OutboxDispatcher(brokenRecorder, transactions).dispatchOne(event -> {
                    throw new IllegalStateException("injected delivery failure");
                }));
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(appender.list.stream().anyMatch(entry -> entry.getFormattedMessage().contains(
                "tenantId=tenant-a eventId=delivery-1")));
            assertEquals(0, database.queryForObject("SELECT attempts FROM reservation_outbox", Integer.class));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test void concurrentSuccessPreventsLateFailureRecorderFromChangingDeliveredRow() throws Exception {
        var recording = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delayedRecorder = new JdbcTemplate(java.util.Objects.requireNonNull(database.getDataSource())) {
            @Override public int update(String sql, Object... arguments) {
                if (sql.contains("attempts = attempts + 1")) {
                    recording.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("recorder gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return super.update(sql, arguments);
            }
        };
        var firstDispatcher = new OutboxDispatcher(delayedRecorder, transactions);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> assertThrows(IllegalStateException.class,
                () -> firstDispatcher.dispatchOne(event -> { throw new IllegalStateException("first attempt fails"); })));
            try {
                assertTrue(recording.await(5, TimeUnit.SECONDS));
                assertTrue(dispatcher.dispatchOne(consumer));
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertNotNull(database.queryForObject("SELECT delivered_at FROM reservation_outbox", java.sql.Timestamp.class));
        assertEquals(0, database.queryForObject("SELECT attempts FROM reservation_outbox", Integer.class));
        assertEquals(1, count("consumer_inbox"));
        assertEquals(2L, database.queryForObject("SELECT quantity FROM fulfillment_total WHERE tenant_id='tenant-a'", Long.class));
        System.out.println("RECORDER_RACE success-before-recorder-update delivered=true attempts=0 effect=2");
    }

    @Test void crashAfterConsumerCommitRetriesWithoutRepeatingEffect() {
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOne(event -> {
            consumer.accept(event);
            throw new IllegalStateException("lost acknowledgement after consumer commit");
        }));
        assertEquals(1, count("consumer_inbox"));
        assertEquals(2L, database.queryForObject("SELECT quantity FROM fulfillment_total", Long.class));
        assertNull(database.queryForObject("SELECT delivered_at FROM reservation_outbox", java.sql.Timestamp.class));
        retryNow();
        assertTrue(dispatcher.dispatchOne(consumer));
        assertFalse(dispatcher.dispatchOne(consumer));
        assertEquals(1, count("consumer_inbox"));
        assertEquals(2L, database.queryForObject("SELECT quantity FROM fulfillment_total", Long.class));
        System.out.println("CRASH_RETRY inbox=1 effectQuantity=2 delivered=1 retryDuplicateSuppressed=true");
    }

    @Test void failureBeforeDeliveryLeavesPendingAndNoConsumerEffect() {
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOne(event -> { throw new IllegalStateException("transport failed"); }));
        assertEquals(0, count("consumer_inbox"));
        retryNow();
        assertTrue(dispatcher.dispatchOne(consumer));
        assertEquals(2L, database.queryForObject("SELECT quantity FROM fulfillment_total", Long.class));
    }

    @Test void inboxAndBusinessEffectRollbackTogether() {
        database.update("UPDATE fulfillment_total SET quantity = ? WHERE tenant_id = 'tenant-a'", Long.MAX_VALUE);
        assertThrows(RuntimeException.class, () -> dispatcher.dispatchOne(consumer));
        assertEquals(0, count("consumer_inbox"));
        database.update("UPDATE fulfillment_total SET quantity = 0 WHERE tenant_id = 'tenant-a'");
        retryNow();
        assertTrue(dispatcher.dispatchOne(consumer));
    }

    @Test void identityReuseWithChangedPayloadIsRejected() {
        var event = new OutboxDispatcher.Event("tenant-a", "same-id", "reservation-1", 1001, 2);
        consumer.accept(event);
        assertThrows(IllegalArgumentException.class, () -> consumer.accept(
            new OutboxDispatcher.Event("tenant-a", "same-id", "reservation-1", 1001, 3)));
        assertEquals(2L, database.queryForObject("SELECT quantity FROM fulfillment_total", Long.class));
    }

    @Test void concurrentConsumersApplyOneEffect() throws Exception {
        var event = new OutboxDispatcher.Event("tenant-a", "same-id", "reservation-1", 1001, 2);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> pool.submit(() -> consumer.accept(event))).toList();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, count("consumer_inbox"));
        assertEquals(2L, database.queryForObject("SELECT quantity FROM fulfillment_total", Long.class));
    }

    @Test void secondDispatcherSkipsLockedRow() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> dispatcher.dispatchOne(event -> {
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                consumer.accept(event);
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                assertFalse(dispatcher.dispatchOne(consumer));
            } finally { release.countDown(); }
            assertTrue(first.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("consumer_inbox"));
    }

    @Test void failingOldestEventDoesNotStarveLaterEventAndIsQuarantined() {
        orders.reserve("tenant-a", 1002, "delivery-2", 3);
        database.update("UPDATE reservation_outbox SET created_at = ? WHERE event_id = 'delivery-1'",
            java.sql.Timestamp.valueOf("2020-01-01 00:00:00"));
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) retryNow();
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOne(event -> {
                if (event.eventId().equals("delivery-1")) throw new IllegalStateException("poison");
                consumer.accept(event);
            }));
            if (attempt == 0) assertTrue(dispatcher.dispatchOne(consumer));
        }
        assertTrue(database.queryForObject("SELECT quarantined FROM reservation_outbox WHERE event_id='delivery-1'", Boolean.class));
        assertEquals(3, database.queryForObject("SELECT attempts FROM reservation_outbox WHERE event_id='delivery-1'", Integer.class));
        assertFalse(dispatcher.dispatchOne(consumer));
        assertEquals(3L, database.queryForObject("SELECT quantity FROM fulfillment_total", Long.class));
    }

    @Test void expiredDispatchTransactionStillRecordsFailureInFreshTransaction() {
        assertThrows(RuntimeException.class, () -> dispatcher.dispatchOne(event -> {
            try { Thread.sleep(5200); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            throw new IllegalStateException("sink failed after transaction deadline");
        }));
        assertEquals(1, database.queryForObject("SELECT attempts FROM reservation_outbox", Integer.class));
        var eligibleAt = database.queryForObject("SELECT next_attempt_at FROM reservation_outbox", java.time.OffsetDateTime.class).toInstant();
        assertTrue(eligibleAt.isAfter(java.time.Instant.now().minusMillis(100)));
        assertTrue(eligibleAt.isBefore(java.time.Instant.now().plusSeconds(2)));
        retryNow();
        assertTrue(dispatcher.dispatchOne(consumer));
    }

    @Test void dispatcherConcurrencyIsRejectedBeforeOpeningThirdTransaction() throws Exception {
        var entered = new CountDownLatch(2);
        var firstEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            OutboxDispatcher.Sink blocking = event -> {
                entered.countDown();
                firstEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                consumer.accept(event);
            };
            var first = pool.submit(() -> dispatcher.dispatchOne(blocking));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            orders.reserve("tenant-a", 1002, "delivery-2", 3);
            database.update("UPDATE reservation_outbox SET created_at = ? WHERE event_id='delivery-2'",
                java.sql.Timestamp.valueOf("2000-01-01 00:00:00"));
            var second = pool.submit(() -> dispatcher.dispatchOne(blocking));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.dispatchOne(consumer));
            } finally { release.countDown(); }
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(5L, database.queryForObject("SELECT quantity FROM fulfillment_total", Long.class));
    }
}
