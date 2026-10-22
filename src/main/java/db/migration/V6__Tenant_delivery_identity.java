package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V6__Tenant_delivery_identity extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        requireZero(connection, "SELECT CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END FROM fulfillment_total",
            "Expected exactly one legacy total");
        requireZero(connection, "SELECT COUNT(*) FROM consumer_inbox inbox LEFT JOIN customer_order orders "
            + "ON inbox.order_id = orders.id WHERE orders.id IS NULL", "Inbox has no authoritative tenant");
        requireZero(connection, "SELECT COUNT(*) FROM reservation_outbox outbox LEFT JOIN reservation reservation "
            + "ON outbox.reservation_id = reservation.id WHERE reservation.id IS NULL "
            + "OR outbox.order_id <> reservation.order_id OR outbox.event_id <> reservation.event_id "
            + "OR outbox.quantity <> reservation.quantity", "Outbox differs from reservation");
        requireZero(connection, "SELECT COUNT(*) FROM consumer_inbox inbox LEFT JOIN reservation_outbox outbox "
            + "ON inbox.event_id = outbox.event_id WHERE outbox.event_id IS NULL "
            + "OR inbox.reservation_id <> outbox.reservation_id OR inbox.order_id <> outbox.order_id "
            + "OR inbox.quantity <> outbox.quantity", "Inbox has no matching producer envelope");
        requireZero(connection, "SELECT COUNT(*) FROM fulfillment_total WHERE quantity <> "
            + "(SELECT COALESCE(SUM(quantity), 0) FROM consumer_inbox)", "Legacy total does not reconcile");
        execute(connection, "ALTER TABLE reservation ADD COLUMN tenant_id VARCHAR(64)");
        execute(connection, "UPDATE reservation SET tenant_id = (SELECT tenant_id FROM customer_order WHERE id = reservation.order_id)");
        execute(connection, "ALTER TABLE reservation ALTER COLUMN tenant_id SET NOT NULL");
        execute(connection, "ALTER TABLE customer_order ADD CONSTRAINT tenant_order_identity UNIQUE (tenant_id, id)");
        execute(connection, "ALTER TABLE reservation ADD CONSTRAINT reservation_tenant_order FOREIGN KEY (tenant_id, order_id) REFERENCES customer_order(tenant_id, id)");
        execute(connection, "ALTER TABLE reservation ADD CONSTRAINT tenant_reservation_identity UNIQUE (tenant_id, id)");
        execute(connection, "ALTER TABLE reservation DROP CONSTRAINT one_reservation_per_event");
        execute(connection, "ALTER TABLE reservation ADD CONSTRAINT tenant_request_identity UNIQUE (tenant_id, event_id)");
        for (String table : new String[] {"reservation_outbox", "consumer_inbox"}) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN tenant_id VARCHAR(64)");
            execute(connection, "UPDATE " + table + " SET tenant_id = (SELECT tenant_id FROM customer_order WHERE id = " + table + ".order_id)");
            execute(connection, "ALTER TABLE " + table + " ALTER COLUMN tenant_id SET NOT NULL");
            dropPrimaryKey(connection, table);
            execute(connection, "ALTER TABLE " + table + " ADD PRIMARY KEY (tenant_id, event_id)");
        }
        execute(connection, "ALTER TABLE reservation_outbox ADD CONSTRAINT outbox_tenant_reservation FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservation(tenant_id, id)");
        execute(connection, "ALTER TABLE reservation_outbox ADD CONSTRAINT outbox_tenant_order FOREIGN KEY (tenant_id, order_id) REFERENCES customer_order(tenant_id, id)");
        execute(connection, "ALTER TABLE fulfillment_total RENAME TO fulfillment_total_legacy");
        execute(connection, "CREATE TABLE fulfillment_total (tenant_id VARCHAR(64) PRIMARY KEY, quantity BIGINT NOT NULL)");
        execute(connection, "INSERT INTO fulfillment_total SELECT orders.tenant_id, COALESCE(SUM(inbox.quantity), 0) "
            + "FROM (SELECT DISTINCT tenant_id FROM customer_order) orders LEFT JOIN consumer_inbox inbox "
            + "ON orders.tenant_id = inbox.tenant_id GROUP BY orders.tenant_id");
    }

    private void requireZero(Connection connection, String sql, String message) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            if (rows.getLong(1) != 0) throw new SQLException(message);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void dropPrimaryKey(Connection connection, String table) throws SQLException {
        String name = null;
        var metadata = connection.getMetaData();
        for (String candidate : new String[] {table, table.toUpperCase(java.util.Locale.ROOT)}) {
            try (var keys = metadata.getPrimaryKeys(connection.getCatalog(), connection.getSchema(), candidate)) {
                if (keys.next()) name = keys.getString("PK_NAME");
            }
            if (name != null) break;
        }
        if (name == null) throw new SQLException("No primary key found for " + table);
        execute(connection, "ALTER TABLE " + table + " DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
    }
}
