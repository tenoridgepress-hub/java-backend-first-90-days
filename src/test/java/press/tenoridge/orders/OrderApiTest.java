package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(20)
class OrderApiTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate database;
    @Autowired OrderService orders;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final HttpClient client = HttpClient.newHttpClient();
    private static final String SECRET = "local-training-key-32-bytes-minimum-never-deploy";

    @BeforeEach
    void reset() {
        database.update("DELETE FROM reservation_outbox");
        database.update("DELETE FROM reservation");
    }

    private String token(String scope, long expiration, String issuer, String secret) throws Exception {
        return token(scope, expiration, issuer, secret, "[\"ridge-orders\"]", "\"tenant-a\"");
    }

    private String token(String scope, long expiration, String issuer, String secret, String audience, String tenant) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":\"reader\",\"iss\":\"" + issuer
            + "\",\"aud\":" + audience + ",\"tenant_id\":" + tenant + ",\"scope\":\"" + scope + "\",\"exp\":" + expiration + "}").getBytes(StandardCharsets.UTF_8));
        var signer = Mac.getInstance("HmacSHA256");
        signer.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String unsigned = header + "." + payload;
        return unsigned + "." + encoder.encodeToString(signer.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
    }

    private String valid(String scope) throws Exception {
        return token(scope, System.currentTimeMillis() / 1000 + 600, "ridge-local", SECRET);
    }

    private HttpResponse<String> call(String method, String path, String bearer, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(java.time.Duration.ofSeconds(10)).header("X-Request-Id", "exercise-1001");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> reserve(String eventId, int quantity) throws Exception {
        return call("POST", "/api/orders/1001/reservations", valid("orders.write"),
            "{\"eventId\":\"" + eventId + "\",\"quantity\":" + quantity + "}");
    }

    @Test void reservationResponseHasStableFieldsAndRejectionsPreserveBothTables() throws Exception {
        var response = reserve("contract", 2);
        var json = new tools.jackson.databind.ObjectMapper().readTree(response.body());
        assertEquals(200, response.statusCode());
        assertEquals(4, json.size());
        assertEquals("contract", json.get("eventId").asText());
        assertEquals(1001L, json.get("orderId").asLong());
        assertEquals(2, json.get("quantity").asInt());
        assertDoesNotThrow(() -> java.util.UUID.fromString(json.get("id").asText()));
        var reservations = database.queryForList("SELECT * FROM reservation ORDER BY id");
        var outbox = database.queryForList("SELECT * FROM reservation_outbox ORDER BY tenant_id,event_id");
        assertEquals(409, reserve("contract-changed", 2).statusCode());
        assertEquals(400, reserve("contract", 0).statusCode());
        assertEquals(403, call("POST", "/api/orders/1001/reservations", valid("orders.read"),
            "{\"eventId\":\"contract\",\"quantity\":2}").statusCode());
        assertEquals(reservations, database.queryForList("SELECT * FROM reservation ORDER BY id"));
        assertEquals(outbox, database.queryForList("SELECT * FROM reservation_outbox ORDER BY tenant_id,event_id"));
        System.out.println("API_CONTRACT reservation=id,orderId,eventId,quantity rejected=409,400,403 both-tables=unchanged");
    }

    @Test void cursorIsAnOrderingBoundaryRatherThanASnapshot() throws Exception {
        var first = call("GET", "/api/orders?limit=1", valid("orders.read"), null);
        assertTrue(first.body().contains("\"nextAfter\":1001"));
        database.update("INSERT INTO customer_order VALUES (999,'late-before-cursor',1,'tenant-a')");
        database.update("INSERT INTO customer_order VALUES (1004,'late-after-cursor',1,'tenant-a')");
        try {
            var next = call("GET", "/api/orders?after=1001&limit=100", valid("orders.read"), null);
            assertFalse(next.body().contains("late-before-cursor"));
            assertTrue(next.body().contains("late-after-cursor"));
            assertTrue(next.body().indexOf("fictional-customer-b") < next.body().indexOf("late-after-cursor"));
            System.out.println("CURSOR after=1001 late-id999=excluded late-id1004=included");
        } finally {
            database.update("DELETE FROM customer_order WHERE id IN (999,1004)");
        }
    }

    @Test void fractionalQuantityIsRejectedWithoutChangingDurableState() throws Exception {
        var reservations = database.queryForList("SELECT * FROM reservation ORDER BY id");
        var outbox = database.queryForList("SELECT * FROM reservation_outbox ORDER BY tenant_id,event_id");
        for (String quantity : List.of("2.9", "2.0", "2e0")) {
            var response = call("POST", "/api/orders/1001/reservations", valid("orders.write"),
                "{\"eventId\":\"strict-integer\",\"quantity\":" + quantity + "}");
            assertEquals(400, response.statusCode(), quantity);
            var json = new tools.jackson.databind.ObjectMapper().readTree(response.body());
            assertEquals("invalid_request", json.get("code").asText());
            assertEquals("exercise-1001", json.get("requestId").asText());
            assertEquals(reservations, database.queryForList("SELECT * FROM reservation ORDER BY id"));
            assertEquals(outbox, database.queryForList("SELECT * FROM reservation_outbox ORDER BY tenant_id,event_id"));
        }
        assertEquals(200, reserve("strict-integer", 2).statusCode());
    }

    @Test void healthIsPublic() throws Exception {
        assertEquals(200, call("GET", "/actuator/health", null, null).statusCode());
    }

    @Test void audienceAndTenantClaimsFailClosed() throws Exception {
        long future = System.currentTimeMillis() / 1000 + 600;
        for (String audience : List.of("[\"other-api\"]", "[]", "null")) {
            assertEquals(401, call("GET", "/api/orders/1001",
                token("orders.read", future, "ridge-local", SECRET, audience, "\"tenant-a\""), null).statusCode());
        }
        for (String tenant : List.of("null", "\"\"", "17", "[\"tenant-a\"]", "\"bad tenant\"")) {
            assertEquals(401, call("GET", "/api/orders/1001",
                token("orders.read", future, "ridge-local", SECRET, "[\"ridge-orders\"]", tenant), null).statusCode());
        }
    }

    @Test void foreignTenantCannotReadListReserveOrQuoteOrder() throws Exception {
        String bearer = token("orders.read orders.write", System.currentTimeMillis() / 1000 + 600,
            "ridge-local", SECRET, "[\"ridge-orders\"]", "\"tenant-b\"");
        assertTrue(call("GET", "/api/orders", bearer, null).body().contains("\"items\":[]"));
        for (String path : List.of("/api/orders/1001", "/api/orders/1001/shipping")) {
            assertEquals(404, call("GET", path, bearer, null).statusCode());
        }
        assertEquals(404, call("POST", "/api/orders/1001/reservations", bearer,
            "{\"eventId\":\"foreign\",\"quantity\":2}").statusCode());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM reservation_outbox", Integer.class));
    }

    @Test void errorContractMatchesAcrossSecurityAndControllerFailures() throws Exception {
        var responses = List.of(
            call("GET", "/api/orders/1001", null, null),
            call("GET", "/api/orders/1001", valid("orders.write"), null),
            call("GET", "/api/orders/9999", valid("orders.read"), null),
            call("GET", "/api/orders?limit=bad", valid("orders.read"), null),
            reserve("conflict-test", 3),
            call("POST", "/api/orders/1001/reservations", valid("orders.write"), "{"));
        var expected = List.of("unauthenticated", "forbidden", "not_found", "invalid_request", "conflict", "invalid_request");
        var mapper = new tools.jackson.databind.ObjectMapper();
        for (int index = 0; index < responses.size(); index++) {
            var response = responses.get(index);
            var json = mapper.readTree(response.body());
            assertEquals(2, json.size());
            assertEquals(expected.get(index), json.get("code").asText());
            assertEquals("exercise-1001", json.get("requestId").asText());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        }
        assertEquals("Bearer", responses.getFirst().headers().firstValue("WWW-Authenticate").orElseThrow());
        System.out.println("ERROR_CONTRACT security=401/403 input=400 absent=404 conflict=409 fields=code,requestId");
    }

    @Test void populatedTenantsKeepPagesAndRequestIdentitySeparate() throws Exception {
        database.update("INSERT INTO customer_order (id, customer_ref, quantity, tenant_id) VALUES (1003, 'tenant-b-order', 4, 'tenant-b')");
        try {
            String bearer = token("orders.read orders.write", System.currentTimeMillis() / 1000 + 600,
                "ridge-local", SECRET, "[\"ridge-orders\"]", "\"tenant-b\"");
            assertEquals(200, call("GET", "/api/orders/1003", bearer, null).statusCode());
            var page = call("GET", "/api/orders?after=1001&limit=1", bearer, null);
            assertTrue(page.body().contains("tenant-b-order"));
            assertFalse(page.body().contains("fictional-customer"));
            assertEquals(404, call("GET", "/api/orders/1003", valid("orders.read"), null).statusCode());
            assertEquals(200, reserve("global-policy", 2).statusCode());
            assertEquals(200, call("POST", "/api/orders/1003/reservations", bearer,
                "{\"eventId\":\"global-policy\",\"quantity\":4}").statusCode());
            assertEquals(409, call("POST", "/api/orders/1003/reservations", bearer,
                "{\"eventId\":\"tenant-b-own\",\"quantity\":4}").statusCode());
        } finally {
            database.update("DELETE FROM reservation_outbox WHERE order_id=1003");
            database.update("DELETE FROM reservation WHERE order_id=1003");
            database.update("DELETE FROM customer_order WHERE id=1003");
        }
    }

    @Test void methodAndMediaFailuresKeepCodesAndProtocolHeaders() throws Exception {
        var method = call("DELETE", "/api/orders/1001", valid("orders.read"), null);
        assertEquals(405, method.statusCode());
        assertTrue(method.headers().firstValue("Allow").orElseThrow().contains("GET"));
        assertTrue(method.body().contains("method_not_allowed"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders/1001/reservations"))
            .timeout(java.time.Duration.ofSeconds(5)).header("Authorization", "Bearer " + valid("orders.write"))
            .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("hello")).build();
        var media = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(415, media.statusCode());
        assertTrue(media.body().contains("unsupported_media_type"));
        var unacceptable = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders/1001"))
            .timeout(java.time.Duration.ofSeconds(5)).header("Authorization", "Bearer " + valid("orders.read"))
            .header("Accept", "application/xml").GET().build();
        var negotiation = client.send(unacceptable, HttpResponse.BodyHandlers.ofString());
        assertEquals(406, negotiation.statusCode());
        assertTrue(negotiation.body().contains("not_acceptable"));
    }

    @Test void reservationAndOutboxAreAtomicAndReplayDoesNotDuplicateEvent() throws Exception {
        var original = reserve("atomic-event", 2);
        var replay = reserve("atomic-event", 2);
        assertEquals(200, original.statusCode());
        assertEquals(original.body(), replay.body());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM reservation_outbox", Integer.class));
        var row = database.queryForMap("SELECT event_id, reservation_id, order_id, quantity FROM reservation_outbox");
        assertEquals("atomic-event", row.get("event_id"));
        assertTrue(original.body().contains(row.get("reservation_id").toString()));
        assertEquals(1001, ((Number) row.get("order_id")).longValue());
        assertEquals(2, ((Number) row.get("quantity")).intValue());
    }

    @Test void failureBeforeCommitRollsBackReservationAndOutbox() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            orders.reserve("tenant-a", 1001, "rollback-event", 2);
            assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM reservation_outbox", Integer.class));
            throw new IllegalStateException("simulate failure before commit");
        }));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM reservation_outbox", Integer.class));
    }

    @Test void outboxInsertFailureRollsBackNewReservation() throws Exception {
        assertEquals(200, call("POST", "/api/orders/1002/reservations", valid("orders.write"),
            "{\"eventId\":\"existing-reservation\",\"quantity\":3}").statusCode());
        database.update("UPDATE reservation_outbox SET event_id = ? WHERE order_id = ?", "blocked-event", 1002);
        var reservations = database.queryForList("SELECT * FROM reservation ORDER BY id");
        var outbox = database.queryForList("SELECT * FROM reservation_outbox ORDER BY event_id");
        assertEquals(409, reserve("blocked-event", 2).statusCode());
        assertEquals(reservations, database.queryForList("SELECT * FROM reservation ORDER BY id"));
        assertEquals(outbox, database.queryForList("SELECT * FROM reservation_outbox ORDER BY event_id"));
    }

    @Test void cursorPaginationReturnsOrderedPagesWithoutDuplicates() throws Exception {
        var first = call("GET", "/api/orders?limit=1", valid("orders.read"), null);
        var second = call("GET", "/api/orders?limit=1&after=1001", valid("orders.read"), null);
        var empty = call("GET", "/api/orders?after=1002", valid("orders.read"), null);
        assertAll(
            () -> assertEquals(200, first.statusCode()),
            () -> assertTrue(first.body().contains("\"nextAfter\":1001")),
            () -> assertTrue(first.body().contains("fictional-customer-a")),
            () -> assertFalse(first.body().contains("fictional-customer-b")),
            () -> assertTrue(second.body().contains("fictional-customer-b")),
            () -> assertTrue(second.body().contains("\"nextAfter\":null")),
            () -> assertTrue(empty.body().contains("\"items\":[]")));
        System.out.println("PAGES first=" + first.body() + " second=" + second.body() + " empty=" + empty.body());
    }

    @Test void invalidPaginationAndUnauthorizedListingAreRejected() throws Exception {
        for (String query : List.of("limit=0", "limit=101", "after=-1", "limit=bad")) {
            assertEquals(400, call("GET", "/api/orders?" + query, valid("orders.read"), null).statusCode());
        }
        assertEquals(401, call("GET", "/api/orders", null, null).statusCode());
        assertEquals(403, call("GET", "/api/orders", valid("orders.write"), null).statusCode());
    }

    @Test void unauthenticatedCannotRead() throws Exception {
        assertEquals(401, call("GET", "/api/orders/1001", null, null).statusCode());
    }

    @Test void readScopeCannotWrite() throws Exception {
        assertEquals(403, call("POST", "/api/orders/1001/reservations", valid("orders.read"),
            "{\"eventId\":\"evt-1\",\"quantity\":2}").statusCode());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
    }

    @Test void readsSeedAndCorrelatesRequest() throws Exception {
        var response = call("GET", "/api/orders/1001", valid("orders.read"), null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("fictional-customer-a"));
        assertEquals("exercise-1001", response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test void unknownOrderIs404() throws Exception {
        assertEquals(404, call("GET", "/api/orders/9999", valid("orders.read"), null).statusCode());
    }

    @Test void replayReturnsSameReservation() throws Exception {
        var original = reserve("evt-1", 2);
        var replay = reserve("evt-1", 2);
        int count = database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);
        System.out.println("REPLAY original=" + original.body() + " replay=" + replay.body() + " durableCount=" + count);
        assertAll(
            () -> assertEquals(200, original.statusCode()),
            () -> assertEquals(200, replay.statusCode()),
            () -> assertEquals(original.body(), replay.body()),
            () -> assertEquals(1, count));
    }

    @Test void concurrentReplayCreatesOneRow() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<HttpResponse<String>>> requests = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> (Callable<HttpResponse<String>>) () -> reserve("evt-race", 2)).toList();
            var responses = pool.invokeAll(requests, 10, java.util.concurrent.TimeUnit.SECONDS);
            String first = responses.getFirst().get().body();
            for (var result : responses) {
                assertEquals(200, result.get().statusCode());
                assertEquals(first, result.get().body());
            }
        }
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
    }

    @Test void mismatchedQuantityDoesNotWrite() throws Exception {
        assertEquals(409, reserve("evt-1", 3).statusCode());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
    }

    @Test void distinctEventCannotReserveOrderAgain() throws Exception {
        assertEquals(200, reserve("evt-1", 2).statusCode());
        var before = database.queryForList("SELECT * FROM reservation ORDER BY id");
        assertEquals(409, reserve("evt-2", 2).statusCode());
        assertEquals(before, database.queryForList("SELECT * FROM reservation ORDER BY id"));
    }

    @Test void globalEventCannotMoveToAnotherOrder() throws Exception {
        reserve("evt-1", 2);
        assertEquals(409, call("POST", "/api/orders/1002/reservations", valid("orders.write"),
            "{\"eventId\":\"evt-1\",\"quantity\":3}").statusCode());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
    }

    @Test void invalidInputIs400() throws Exception {
        assertEquals(400, reserve("bad event", 2).statusCode());
        assertEquals(400, reserve("evt-1", 0).statusCode());
        assertEquals(400, call("POST", "/api/orders/1001/reservations", valid("orders.write"),
            "{\"quantity\":2}").statusCode());
    }

    @Test void expiredWrongIssuerAndForgedTokensAreRejected() throws Exception {
        long future = System.currentTimeMillis() / 1000 + 600;
        for (String invalid : List.of(token("orders.read", 1, "ridge-local", SECRET),
                token("orders.read", future, "someone-else", SECRET),
                token("orders.read", future, "ridge-local", "a-different-long-signing-key-32-bytes"))) {
            assertEquals(401, call("GET", "/api/orders/1001", invalid, null).statusCode());
        }
    }

    @Test void metricsRequireOpsScope() throws Exception {
        assertEquals(403, call("GET", "/actuator/metrics", valid("orders.read"), null).statusCode());
        assertEquals(200, call("GET", "/actuator/metrics", valid("ops"), null).statusCode());
    }

    @Test void rejectedWritesPreserveExistingState() throws Exception {
        assertEquals(200, reserve("preserved", 2).statusCode());
        var before = database.queryForList("SELECT * FROM reservation ORDER BY id");
        for (String body : List.of("{\"eventId\":\"preserved\"}",
                "{\"eventId\":\"preserved\",\"quantity\":null}",
                "{\"eventId\":\"preserved\",\"quantity\":0}")) {
            assertEquals(400, call("POST", "/api/orders/1001/reservations", valid("orders.write"), body).statusCode());
            assertEquals(before, database.queryForList("SELECT * FROM reservation ORDER BY id"));
        }
        assertEquals(409, reserve("preserved", 3).statusCode());
        assertEquals(404, call("POST", "/api/orders/9999/reservations", valid("orders.write"),
            "{\"eventId\":\"absent\",\"quantity\":2}").statusCode());
        for (String bearer : java.util.Arrays.asList(null, valid("orders.read"))) {
            assertEquals(bearer == null ? 401 : 403, call("POST", "/api/orders/1001/reservations", bearer,
                "{\"eventId\":\"denied\",\"quantity\":2}").statusCode());
            assertEquals(before, database.queryForList("SELECT * FROM reservation ORDER BY id"));
        }
        assertEquals(before, database.queryForList("SELECT * FROM reservation ORDER BY id"));
    }

    @Test void concurrentEventAcrossOrdersHasOneWinner() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<HttpResponse<String>>> results = new java.util.ArrayList<>();
            for (int index = 0; index < 2; index++) {
                long orderId = 1001 + index;
                int quantity = 2 + index;
                String bearer = valid("orders.write");
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return call("POST", "/api/orders/" + orderId + "/reservations", bearer,
                        "{\"eventId\":\"global-race\",\"quantity\":" + quantity + "}");
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var first = results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS);
            var second = results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(first.statusCode(), second.statusCode()).sorted().toList());
            assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
            int winner = first.statusCode() == 200 ? 0 : 1;
            var stored = database.queryForMap("SELECT order_id, event_id, quantity FROM reservation");
            assertEquals(1001L + winner, ((Number) stored.get("order_id")).longValue());
            assertEquals("global-race", stored.get("event_id"));
            assertEquals(2 + winner, ((Number) stored.get("quantity")).intValue());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
