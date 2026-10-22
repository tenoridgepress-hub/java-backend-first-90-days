package press.tenoridge.labs;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.SpringApplication;
import press.tenoridge.orders.IdempotentConsumer;
import press.tenoridge.orders.OrderService;
import press.tenoridge.orders.OrdersApplication;
import press.tenoridge.orders.OutboxDispatcher;

public class CrashRecoveryLab {
    public static void main(String[] args) throws Exception {
        try (var context = SpringApplication.run(OrdersApplication.class, args)) {
            var environment = context.getEnvironment();
            String phase = environment.getRequiredProperty("ridge.lab.phase");
            Path marker = Path.of(environment.getRequiredProperty("ridge.lab.marker"));
            var orders = context.getBean(OrderService.class);
            var dispatcher = context.getBean(OutboxDispatcher.class);
            var consumer = context.getBean(IdempotentConsumer.class);
            var reservation = orders.reserve("tenant-a", 1001, "restart-same-key", 2);
            if (phase.equals("recover")) {
                boolean delivered = dispatcher.dispatchOne(consumer);
                durableMarker(marker, reservation.id() + " selected=" + delivered);
                return;
            }
            dispatcher.dispatchOne(event -> {
                if (phase.equals("after-effect")) consumer.accept(event);
                if (!phase.equals("after-delivery")) {
                    try {
                        durableMarker(marker, reservation.id() + " phase=" + phase);
                        new CountDownLatch(1).await();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                } else {
                    consumer.accept(event);
                }
            });
            durableMarker(marker, reservation.id() + " phase=after-delivery");
            new CountDownLatch(1).await();
        }
    }

    private static void durableMarker(Path path, String value) throws Exception {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            channel.force(true);
        }
    }
}
