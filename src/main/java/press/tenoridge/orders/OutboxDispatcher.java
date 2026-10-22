package press.tenoridge.orders;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxDispatcher {
    public record Event(String tenantId, String eventId, String reservationId, long orderId, int quantity) {}

    @FunctionalInterface
    public interface Sink {
        void accept(Event event);
    }

    private final JdbcTemplate database;
    private final TransactionTemplate transaction;
    private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(2);

    public OutboxDispatcher(JdbcTemplate database, PlatformTransactionManager transactions) {
        this.database = database;
        this.transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    public boolean dispatchOne(Sink sink) {
        if (!permits.tryAcquire()) throw new java.util.concurrent.RejectedExecutionException("Two dispatcher calls already active");
        var selected = new java.util.concurrent.atomic.AtomicReference<Event>();
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                var pending = database.query(
                    "SELECT tenant_id, event_id, reservation_id, order_id, quantity FROM reservation_outbox "
                        + "WHERE delivered_at IS NULL AND quarantined = FALSE AND next_attempt_at <= CURRENT_TIMESTAMP "
                        + "ORDER BY created_at, tenant_id, event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (row, index) -> new Event(row.getString(1), row.getString(2), row.getString(3), row.getLong(4), row.getInt(5)));
                if (pending.isEmpty()) return false;
                var event = pending.getFirst();
                selected.set(event);
                sink.accept(event);
                database.update("UPDATE reservation_outbox SET delivered_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND event_id = ?",
                    event.tenantId(), event.eventId());
                return true;
            }));
        } catch (RuntimeException failure) {
            var event = selected.get();
            if (event != null) {
                try {
                    transaction.executeWithoutResult(status -> database.update(
                        "UPDATE reservation_outbox SET attempts = attempts + 1, next_attempt_at = ?, "
                            + "quarantined = CASE WHEN attempts + 1 >= 3 THEN TRUE ELSE FALSE END "
                            + "WHERE tenant_id = ? AND event_id = ? AND delivered_at IS NULL",
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(1), event.tenantId(), event.eventId()));
                } catch (RuntimeException recordingFailure) {
                    failure.addSuppressed(recordingFailure);
                    org.slf4j.LoggerFactory.getLogger(OutboxDispatcher.class)
                        .error("Unable to persist failed dispatch attempt tenantId={} eventId={}",
                            event.tenantId(), event.eventId(), recordingFailure);
                }
            }
            throw failure;
        } finally {
            permits.release();
        }
    }
}
