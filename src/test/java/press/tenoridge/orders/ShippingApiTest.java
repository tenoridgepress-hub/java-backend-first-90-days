package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(20)
class ShippingApiTest {
    private static final HttpServer PEER = peer();
    private static volatile byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
    @Value("${local.server.port}") int port;
    @Autowired MeterRegistry metrics;

    private static HttpServer peer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/quote/1001", exchange -> {
                byte[] current = body;
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, current.length);
                try (var stream = exchange.getResponseBody()) {
                    stream.write(current);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("ridge.shipping.url", () -> "http://127.0.0.1:" + PEER.getAddress().getPort());
        registry.add("ridge.shipping.timeout-ms", () -> 2000);
    }

    @AfterAll static void stopPeer() {
        PEER.stop(0);
    }

    @Test void malformedPeerCannotBecomeSuccessfulJsonAtApiBoundary() throws Exception {
        double beforeSuccess = count("success");
        double beforeInvalid = count("invalid_response");
        for (byte[] invalid : new byte[][]{"not-json".getBytes(StandardCharsets.UTF_8),
            "{\"cents\":\"499\"}".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xc3, 0x28}}) {
            body = invalid;
            var response = call();
            assertEquals(502, response.statusCode());
            assertFalse(response.body().contains("not-json"));
        }
        assertEquals(beforeSuccess, count("success"));
        assertEquals(beforeInvalid + 3, count("invalid_response"));
        body = "{\"cents\":499,\"currency\":\"USD\"}".getBytes(StandardCharsets.UTF_8);
        var recovered = call();
        assertEquals(200, recovered.statusCode());
        assertTrue(recovered.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(499, new tools.jackson.databind.ObjectMapper().readTree(recovered.body()).get("cents").asInt());
        assertEquals(beforeSuccess + 1, count("success"));
    }

    private double count(String outcome) {
        var counter = metrics.find("ridge.shipping.calls").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private HttpResponse<String> call() throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":\"reader\",\"iss\":\"ridge-local\",\"aud\":[\"ridge-orders\"],"
            + "\"tenant_id\":\"tenant-a\",\"scope\":\"orders.read\",\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}")
            .getBytes(StandardCharsets.UTF_8));
        var signer = Mac.getInstance("HmacSHA256");
        signer.init(new SecretKeySpec("local-training-key-32-bytes-minimum-never-deploy".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String unsigned = header + "." + payload;
        String token = unsigned + "." + encoder.encodeToString(signer.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders/1001/shipping"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
