package press.tenoridge.orders;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class LimitedBodySubscriberTest {
    private static class Subscription implements Flow.Subscription {
        boolean cancelled;
        long requested;
        public void request(long count) { requested += count; }
        public void cancel() { cancelled = true; }
    }

    @Test void acceptsExactLimitAndSplitUtf8Character() {
        var subscriber = new LimitedBodySubscriber(3);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        byte[] encoded = "€".getBytes(StandardCharsets.UTF_8);
        subscriber.onNext(List.of(ByteBuffer.wrap(encoded, 0, 1)));
        subscriber.onNext(List.of(ByteBuffer.wrap(encoded, 1, 2)));
        subscriber.onComplete();
        assertEquals("€", subscriber.getBody().toCompletableFuture().join());
        assertFalse(subscription.cancelled);
        assertEquals(3, subscription.requested);
    }

    @Test void rejectsCumulativeOverflowAndCancelsSubscription() {
        var subscriber = new LimitedBodySubscriber(3);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        subscriber.onComplete();
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        assertTrue(subscription.cancelled);
        assertEquals(2, subscription.requested);
    }

    @Test void propagatesTransportFailureAndRejectsDuplicateSubscription() {
        var subscriber = new LimitedBodySubscriber(8);
        var first = new Subscription();
        var duplicate = new Subscription();
        subscriber.onSubscribe(first);
        subscriber.onSubscribe(duplicate);
        var failure = new java.io.IOException("connection closed");
        subscriber.onError(failure);
        assertSame(failure, assertThrows(CompletionException.class,
            () -> subscriber.getBody().toCompletableFuture().join()).getCause());
        assertTrue(duplicate.cancelled);
        assertFalse(first.cancelled);
    }
}
