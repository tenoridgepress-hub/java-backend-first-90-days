package press.tenoridge.orders;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
    private final JdbcTemplate database;

    public OrderService(JdbcTemplate database) {
        this.database = database;
    }

    public record Order(long id, String customerRef, int quantity) {}
    public record Reservation(String id, long orderId, String eventId, int quantity) {}
    public record OrderPage(java.util.List<Order> items, Long nextAfter) {}

    public OrderPage page(String tenant, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "after >= 0 and 1 <= limit <= 100 required");
        }
        var rows = database.query(
            "SELECT id, customer_ref, quantity FROM customer_order WHERE tenant_id = ? AND id > ? ORDER BY id LIMIT ?",
            (row, index) -> new Order(row.getLong(1), row.getString(2), row.getInt(3)), tenant, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = java.util.List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new OrderPage(items, more ? items.getLast().id() : null);
    }

    public Order find(String tenant, long orderId) {
        return database.query("SELECT id, customer_ref, quantity FROM customer_order WHERE tenant_id = ? AND id = ?",
            (row, index) -> new Order(row.getLong(1), row.getString(2), row.getInt(3)), tenant, orderId)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Reservation reserve(String tenant, long orderId, String eventId, int quantity) {
        var quantities = database.queryForList(
            "SELECT quantity FROM customer_order WHERE tenant_id = ? AND id = ? FOR UPDATE", Integer.class, tenant, orderId);
        if (quantities.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (quantities.getFirst() != quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Quantity does not match order");
        }
        var existing = database.query(
            "SELECT id, order_id, event_id, quantity FROM reservation WHERE tenant_id = ? AND order_id = ?",
            (row, index) -> new Reservation(row.getString(1), row.getLong(2), row.getString(3), row.getInt(4)), tenant, orderId);
        if (!existing.isEmpty()) {
            var reservation = existing.getFirst();
            if (!reservation.eventId().equals(eventId) || reservation.quantity() != quantity) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already reserved by another event");
            }
            return reservation;
        }
        var reservation = new Reservation(UUID.randomUUID().toString(), orderId, eventId, quantity);
        database.update("INSERT INTO reservation (tenant_id, id, order_id, event_id, quantity) VALUES (?, ?, ?, ?, ?)",
            tenant, reservation.id(), orderId, eventId, quantity);
        database.update("INSERT INTO reservation_outbox (tenant_id, event_id, reservation_id, order_id, quantity) VALUES (?, ?, ?, ?, ?)",
            tenant, eventId, reservation.id(), orderId, quantity);
        return reservation;
    }
}
