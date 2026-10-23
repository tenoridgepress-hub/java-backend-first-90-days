package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

@Timeout(10)
class ShippingClientTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{", "null", "[]", "499", "{}", "{\"cents\":null}",
        "{\"cents\":true}", "{\"cents\":\"499\"}", "{\"cents\":1.5}", "{\"cents\":1e2}",
        "{\"cents\":-1}", "{\"cents\":9223372036854775808}", "{\"cents\":999999999999999999999}",
        "{\"cents\":1,\"cents\":2}", "{\"cents\":1}{}", "{\"cents\":1}garbage",
        "{\"cents\":1,}", "{\"cents\":1,\"extra\":0}", "{\"cents\":{\"value\":1}}",
        "{\"cents\":1,\"currency\":null}", "{\"cents\":1,\"currency\":123}",
        "{\"cents\":1,\"currency\":\"usd\"}", "{\"cents\":1,\"currency\":\"US\"}",
        "{\"cents\":1,\"currency\":\"USD\",\"currency\":\"EUR\"}",
        "{\"cents\":1,\"currency\":\"\\uD800\"}", "{\"cents\":01}", "/*quote*/{\"cents\":1}"})
    void invalidQuoteReturns502WithoutSuccessAndRecovers(String body) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.body = body.getBytes(StandardCharsets.UTF_8);
            assertEquals(502, assertThrows(ResponseStatusException.class, () -> fixture.client.quote(1001)).getStatusCode().value());
            assertNull(fixture.metrics.find("ridge.shipping.calls").tag("outcome", "success").counter());
            assertEquals(1, fixture.metrics.get("ridge.shipping.calls").tag("outcome", "invalid_response").counter().count());
            assertEquals(1, fixture.metrics.get("ridge.shipping.duration").tag("outcome", "invalid_response").timer().count());
            fixture.body = "{\"cents\":499}".getBytes(StandardCharsets.UTF_8);
            assertEquals("{\"cents\":499}", fixture.client.quote(1001));
            assertEquals(1, fixture.metrics.get("ridge.shipping.calls").tag("outcome", "success").counter().count());
            assertEquals(1, fixture.metrics.get("ridge.shipping.available.permits").gauge().value());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"cents\":0}", "{\"cents\":9223372036854775807}",
        "{\"cents\":499,\"currency\":\"USD\"}", " {\"currency\":\"EUR\",\"cents\":1} \n"})
    void validQuotePreservesBody(String body) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.body = body.getBytes(StandardCharsets.UTF_8);
            fixture.contentType = "application/json; charset=UTF-8";
            assertEquals(body, fixture.client.quote(1001));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json; charset=ISO-8859-1", "application/json; charset=invalid"})
    void incompatibleContentTypeReturns502(String contentType) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.contentType = contentType;
            assertEquals(502, assertThrows(ResponseStatusException.class, () -> fixture.client.quote(1001)).getStatusCode().value());
        }
    }

    @Test void invalidUtf8Returns502WithoutSuccess() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.body = new byte[]{(byte) 0xc3, 0x28};
            assertEquals(502, assertThrows(ResponseStatusException.class, () -> fixture.client.quote(1001)).getStatusCode().value());
            assertEquals(1, fixture.metrics.get("ridge.shipping.calls").tag("outcome", "invalid_response").counter().count());
            assertNull(fixture.metrics.find("ridge.shipping.calls").tag("outcome", "success").counter());
        }
    }

    @Test void compressedContentReturns502() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.contentEncoding = "gzip";
            assertEquals(502, assertThrows(ResponseStatusException.class, () -> fixture.client.quote(1001)).getStatusCode().value());
        }
    }

    @Test void boundedStringsAndNestedStructuresReturn502() throws Exception {
        invalidQuoteReturns502WithoutSuccessAndRecovers("{\"cents\":1,\"currency\":\"" + "A".repeat(300) + "\"}");
        invalidQuoteReturns502WithoutSuccessAndRecovers("{\"cents\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}");
    }
    @Test void downstreamRejectionReturns502() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.status = 503;
            assertEquals(502, assertThrows(ResponseStatusException.class, () -> fixture.client.quote(1001)).getStatusCode().value());
        }
    }

    @Test void returnsSuccessfulQuote() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals("{\"cents\":499}", fixture.client.quote(1001));
        }
    }

    @Test void delayedHeadersRespectDeadline() throws Exception {
        verifyDeadline("headers");
    }

    @Test void timelyHeadersAndDelayedBodyRespectDeadline() throws Exception {
        verifyDeadline("body");
    }

    @Test void stalledBodyRespectDeadline() throws Exception {
        verifyDeadline("stall");
    }

    private void verifyDeadline(String mode) throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals("{\"cents\":499}", fixture.client.quote(1001));
            fixture.mode = mode;
            long started = System.nanoTime();
            var failure = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(ResponseStatusException.class, () -> fixture.client.quote(1001)));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertAll(
                () -> assertEquals(504, failure.getStatusCode().value()),
                () -> assertTrue(elapsedMs >= 180 && elapsedMs < 900, "elapsed=" + elapsedMs),
                () -> assertTrue(fixture.reached.await(1, TimeUnit.SECONDS)));
            System.out.println("DEADLINE mode=" + mode + " configuredMs=250 elapsedMs=" + elapsedMs + " status=504");
            fixture.mode = "healthy";
            assertEquals("{\"cents\":499}", fixture.client.quote(1001));
        }
    }

    @Test void interruptionRestoresFlagAndCancelsWait() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.mode = "stall";
            var interrupted = new AtomicBoolean();
            var status = new java.util.concurrent.atomic.AtomicInteger();
            var worker = new Thread(() -> {
                try {
                    fixture.client.quote(1001);
                } catch (ResponseStatusException failure) {
                    status.set(failure.getStatusCode().value());
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                worker.start();
                assertTrue(fixture.reached.await(2, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(2000);
                assertAll(() -> assertFalse(worker.isAlive()), () -> assertEquals(503, status.get()),
                    () -> assertTrue(interrupted.get()));
            } finally {
                worker.interrupt();
                worker.join(2000);
            }
        }
    }

    @Test void rejectsNonpositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new ShippingClient("http://127.0.0.1", 0));
    }

    @Test void oversizedBodyReturns502ThenRecovers() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.mode = "oversize";
            assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> fixture.client.quote(1001)).getStatusCode().value());
            fixture.mode = "healthy";
            assertEquals("{\"cents\":499}", fixture.client.quote(1001));
            assertEquals(1, fixture.metrics.get("ridge.shipping.calls").tag("outcome", "failure").counter().count());
            assertEquals(1, fixture.metrics.get("ridge.shipping.calls").tag("outcome", "success").counter().count());
        }
    }

    @Test void saturatedClientRejectsImmediatelyAndReleasesPermitAfterTimeout() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.mode = "stall";
            var pool = Executors.newSingleThreadExecutor();
            try {
                var first = pool.submit(() -> assertThrows(ResponseStatusException.class,
                    () -> fixture.client.quote(1001)).getStatusCode().value());
                assertTrue(fixture.reached.await(2, TimeUnit.SECONDS));
                long started = System.nanoTime();
                assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> fixture.client.quote(1001)).getStatusCode().value());
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 150);
                assertEquals(504, first.get(2, TimeUnit.SECONDS));
                fixture.mode = "healthy";
                assertEquals("{\"cents\":499}", fixture.client.quote(1001));
                assertEquals(1, fixture.metrics.get("ridge.shipping.calls").tag("outcome", "saturated").counter().count());
                assertEquals(1, fixture.metrics.get("ridge.shipping.calls").tag("outcome", "timeout").counter().count());
                assertEquals(1, fixture.metrics.get("ridge.shipping.duration").tag("outcome", "timeout").timer().count());
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    private static class Fixture implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch reached = new CountDownLatch(1);
        final ShippingClient client;
        final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        volatile String mode = "healthy";
        volatile int status = 200;
        volatile byte[] body = "{\"cents\":499}".getBytes(StandardCharsets.UTF_8);
        volatile String contentType = "application/json";
        volatile String contentEncoding = "identity";

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/quote/1001", exchange -> {
                String current = mode;
                byte[] body = current.equals("oversize") ? new byte[32_768]
                    : this.body;
                try {
                    if (current.equals("headers")) {
                        reached.countDown();
                        release.await(1200, TimeUnit.MILLISECONDS);
                    }
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.getResponseHeaders().set("Content-Encoding", contentEncoding);
                    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                    if (body.length == 0) return;
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    if (current.equals("body") || current.equals("stall")) {
                        reached.countDown();
                        release.await(current.equals("stall") ? 5000 : 1200, TimeUnit.MILLISECONDS);
                    }
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException disconnected) {
                } finally {
                    exchange.close();
                }
            });
            server.start();
            client = new ShippingClient("http://127.0.0.1:" + server.getAddress().getPort(), 250, 16_384, 1, metrics);
        }

        public void close() throws Exception {
            release.countDown();
            client.shutdown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
