package press.tenoridge.orders;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IdempotentConsumer implements OutboxDispatcher.Sink {
    private final JdbcTemplate database;
    private final TransactionTemplate transaction;

    public IdempotentConsumer(JdbcTemplate database, PlatformTransactionManager transactions) {
        this.database = database;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    @Override
    public void accept(OutboxDispatcher.Event event) {
        if (event.quantity() <= 0) throw new IllegalArgumentException("Quantity must be positive");
        transaction.executeWithoutResult(status -> {
            database.update("INSERT INTO fulfillment_total (tenant_id, quantity) VALUES (?, 0) ON CONFLICT DO NOTHING", event.tenantId());
            database.queryForObject("SELECT quantity FROM fulfillment_total WHERE tenant_id = ? FOR UPDATE", Long.class, event.tenantId());
            var existing = database.query(
                "SELECT tenant_id, event_id, reservation_id, order_id, quantity FROM consumer_inbox WHERE tenant_id = ? AND event_id = ?",
                (row, index) -> new OutboxDispatcher.Event(row.getString(1), row.getString(2), row.getString(3), row.getLong(4), row.getInt(5)),
                event.tenantId(), event.eventId());
            if (!existing.isEmpty()) {
                if (!existing.getFirst().equals(event)) throw new IllegalArgumentException("Event identity reused with different payload");
                return;
            }
            database.update("INSERT INTO consumer_inbox (tenant_id, event_id, reservation_id, order_id, quantity) VALUES (?, ?, ?, ?, ?)",
                event.tenantId(), event.eventId(), event.reservationId(), event.orderId(), event.quantity());
            database.update("UPDATE fulfillment_total SET quantity = quantity + ? WHERE tenant_id = ?", event.quantity(), event.tenantId());
        });
    }
}
