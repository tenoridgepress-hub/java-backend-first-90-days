package press.tenoridge.orders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ShippingClient {
    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxBodyBytes;
    private final Semaphore permits;
    private final MeterRegistry metrics;

    public ShippingClient(String baseUrl, long timeoutMs) {
        this(baseUrl, timeoutMs, 16_384, 8, new SimpleMeterRegistry());
    }

    @Autowired
    public ShippingClient(@Value("${ridge.shipping.url}") String baseUrl,
                          @Value("${ridge.shipping.timeout-ms}") long timeoutMs,
                          @Value("${ridge.shipping.max-body-bytes:16384}") int maxBodyBytes,
                          @Value("${ridge.shipping.max-in-flight:8}") int maxInFlight,
                          MeterRegistry metrics) {
        this.baseUrl = baseUrl;
        if (timeoutMs <= 0) throw new IllegalArgumentException("Shipping timeout must be positive");
        if (maxBodyBytes <= 0 || maxInFlight <= 0) throw new IllegalArgumentException("Shipping limits must be positive");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxBodyBytes = maxBodyBytes;
        this.permits = new Semaphore(maxInFlight);
        this.metrics = metrics;
        metrics.gauge("ridge.shipping.available.permits", permits, Semaphore::availablePermits);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public String quote(long orderId) {
        long started = System.nanoTime();
        if (!permits.tryAcquire()) {
            metrics.counter("ridge.shipping.calls", "outcome", "saturated").increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Shipping capacity exhausted");
        }
        String outcome = "failure";
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/quote/" + orderId)).timeout(timeout).GET().build();
            pending = client.sendAsync(request, info -> new LimitedBodySubscriber(maxBodyBytes));
            long remaining = timeout.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            var result = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (System.nanoTime() - started >= timeout.toNanos()) throw new TimeoutException();
            if (result.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Shipping unavailable");
            }
            outcome = "success";
            return result.body();
        } catch (InterruptedException interrupted) {
            outcome = "interrupted";
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Request interrupted");
        } catch (TimeoutException timeoutFailure) {
            outcome = "timeout";
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Shipping timed out");
        } catch (ExecutionException transportFailure) {
            org.slf4j.LoggerFactory.getLogger(ShippingClient.class).debug("Shipping transport failure", transportFailure.getCause());
            if (transportFailure.getCause() instanceof java.net.http.HttpTimeoutException) {
                outcome = "timeout";
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Shipping timed out");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Shipping unavailable");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            permits.release();
            metrics.counter("ridge.shipping.calls", "outcome", outcome).increment();
            metrics.timer("ridge.shipping.duration", "outcome", outcome)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        client.shutdownNow();
    }
}
