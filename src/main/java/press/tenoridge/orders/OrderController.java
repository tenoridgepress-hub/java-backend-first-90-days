package press.tenoridge.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orders;
    private final ShippingClient shipping;

    public OrderController(OrderService orders, ShippingClient shipping) {
        this.orders = orders;
        this.shipping = shipping;
    }

    public record ReserveRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String eventId,
                                 @Min(1) @Max(100) int quantity) {}

    @GetMapping
    public OrderService.OrderPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long after,
                                       @RequestParam(defaultValue = "20") int limit) {
        return orders.page(jwt.getClaimAsString("tenant_id"), after, limit);
    }

    @GetMapping("/{orderId}")
    public OrderService.Order get(@AuthenticationPrincipal Jwt jwt, @PathVariable long orderId) {
        return orders.find(jwt.getClaimAsString("tenant_id"), orderId);
    }

    @PostMapping("/{orderId}/reservations")
    public OrderService.Reservation reserve(@AuthenticationPrincipal Jwt jwt, @PathVariable long orderId, @Valid @RequestBody ReserveRequest request) {
        return orders.reserve(jwt.getClaimAsString("tenant_id"), orderId, request.eventId(), request.quantity());
    }

    @GetMapping(value = "/{orderId}/shipping", produces = MediaType.APPLICATION_JSON_VALUE)
    public String shipping(@AuthenticationPrincipal Jwt jwt, @PathVariable long orderId) {
        orders.find(jwt.getClaimAsString("tenant_id"), orderId);
        return shipping.quote(orderId);
    }

}
