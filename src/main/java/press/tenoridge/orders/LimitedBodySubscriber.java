package press.tenoridge.orders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<String> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<String> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("Body limit must be positive");
        this.limit = limit;
    }

    @Override
    public CompletionStage<String> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
        if (subscription != null) {
            incoming.cancel();
            return;
        }
        subscription = incoming;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (var buffer : buffers) {
            if (buffer.remaining() > limit - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(new IOException("Shipping body exceeds byte limit"));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
        body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        body.complete(bytes.toString(StandardCharsets.UTF_8));
    }
}
