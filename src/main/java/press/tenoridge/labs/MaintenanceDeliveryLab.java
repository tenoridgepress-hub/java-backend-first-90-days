package press.tenoridge.labs;

import org.springframework.boot.SpringApplication;
import press.tenoridge.orders.IdempotentConsumer;
import press.tenoridge.orders.OrdersApplication;
import press.tenoridge.orders.OutboxDispatcher;

public class MaintenanceDeliveryLab {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(OrdersApplication.class, args)) {
            var dispatcher = context.getBean(OutboxDispatcher.class);
            var consumer = context.getBean(IdempotentConsumer.class);
            int delivered = 0;
            while (delivered < 2000 && dispatcher.dispatchOne(consumer)) delivered++;
            if (delivered == 2000) throw new IllegalStateException("Maintenance row limit reached");
            System.out.println("MANUAL_MAINTENANCE delivered=" + delivered);
        }
    }
}
