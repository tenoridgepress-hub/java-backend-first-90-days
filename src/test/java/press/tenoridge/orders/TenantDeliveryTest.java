package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Timeout(20)
class TenantDeliveryTest {
    @Autowired JdbcTemplate database;
    @Autowired OrderService orders;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired IdempotentConsumer consumer;

    @BeforeEach void reset() {
        for (String table : List.of("consumer_inbox", "reservation_outbox", "reservation")) database.update("DELETE FROM " + table);
        database.update("DELETE FROM customer_order WHERE id=2001");
        database.update("INSERT INTO customer_order (id,customer_ref,quantity,tenant_id) VALUES (2001,'b',3,'tenant-b')");
        database.update("DELETE FROM fulfillment_total");
        database.update("INSERT INTO fulfillment_total VALUES ('tenant-a',0),('tenant-b',0)");
    }

    @AfterEach void cleanup() {
        for (String table : List.of("consumer_inbox", "reservation_outbox", "reservation")) database.update("DELETE FROM " + table);
        database.update("DELETE FROM customer_order WHERE id=2001");
        database.update("DELETE FROM fulfillment_total WHERE tenant_id='tenant-b'");
        database.update("UPDATE fulfillment_total SET quantity=0 WHERE tenant_id='tenant-a'");
    }

    @Test void identicalRequestKeysAreIndependentAcrossTenantsAndEffects() {
        var first = orders.reserve("tenant-a",1001,"same",2);
        var second = orders.reserve("tenant-b",2001,"same",3);
        assertNotEquals(first.id(),second.id());
        assertEquals(first,orders.reserve("tenant-a",1001,"same",2));
        assertEquals(second,orders.reserve("tenant-b",2001,"same",3));
        assertTrue(dispatcher.dispatchOne(consumer));
        assertEquals(1,database.queryForObject("SELECT COUNT(*) FROM reservation_outbox WHERE delivered_at IS NOT NULL",Integer.class));
        assertTrue(dispatcher.dispatchOne(consumer));
        assertEquals(2,database.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id='same'",Integer.class));
        assertEquals(2L,total("tenant-a"));
        assertEquals(3L,total("tenant-b"));
    }

    @Test void failureAccountingCannotQuarantineOtherTenantWithSameKey() {
        orders.reserve("tenant-a",1001,"same",2);
        orders.reserve("tenant-b",2001,"same",3);
        database.update("UPDATE reservation_outbox SET created_at = TIMESTAMP '2000-01-01 00:00:00' WHERE tenant_id='tenant-a'");
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOne(event -> {throw new IllegalStateException("lost");}));
        assertEquals(1,database.queryForObject("SELECT attempts FROM reservation_outbox WHERE tenant_id='tenant-a'",Integer.class));
        assertEquals(0,database.queryForObject("SELECT attempts FROM reservation_outbox WHERE tenant_id='tenant-b'",Integer.class));
        assertTrue(dispatcher.dispatchOne(consumer));
        assertEquals(0L,total("tenant-a"));
        assertEquals(3L,total("tenant-b"));
    }

    @Test void databaseRejectsMismatchedTenantEvenWhenBypassingService() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> database.update(
            "INSERT INTO reservation (tenant_id,id,order_id,event_id,quantity) VALUES ('tenant-b','wrong',1001,'same',2)"));
    }

    @Test void concurrentDuplicateConsumptionIsScopedByTenant() throws Exception {
        var first = new OutboxDispatcher.Event("tenant-a","same","a",1001,2);
        var second = new OutboxDispatcher.Event("tenant-b","same","b",2001,3);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0,16)
                .mapToObj(index -> pool.submit(() -> consumer.accept(index % 2 == 0 ? first : second))).toList();
            for (var task : tasks) task.get(10,TimeUnit.SECONDS);
        }
        assertEquals(2,database.queryForObject("SELECT COUNT(*) FROM consumer_inbox",Integer.class));
        assertEquals(2L,total("tenant-a"));
        assertEquals(3L,total("tenant-b"));
        assertThrows(IllegalArgumentException.class, () -> consumer.accept(
            new OutboxDispatcher.Event("tenant-b","same","b",2001,4)));
    }

    @Test void concurrentFirstEventsCreateOneTenantTotalWithoutPreprovisioning() throws Exception {
        database.update("DELETE FROM fulfillment_total WHERE tenant_id='tenant-b'");
        var first = new OutboxDispatcher.Event("tenant-b","first","b1",2001,3);
        var second = new OutboxDispatcher.Event("tenant-b","second","b2",2001,4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0,16)
                .mapToObj(index -> pool.submit(() -> consumer.accept(index % 2 == 0 ? first : second))).toList();
            for (var task : tasks) task.get(10,TimeUnit.SECONDS);
        }
        assertEquals(7L,total("tenant-b"));
        assertEquals(1,database.queryForObject("SELECT COUNT(*) FROM fulfillment_total WHERE tenant_id='tenant-b'",Integer.class));
        assertEquals(2,database.queryForObject("SELECT COUNT(*) FROM consumer_inbox",Integer.class));
    }

    private long total(String tenant) {
        return database.queryForObject("SELECT quantity FROM fulfillment_total WHERE tenant_id=?",Long.class,tenant);
    }
}
