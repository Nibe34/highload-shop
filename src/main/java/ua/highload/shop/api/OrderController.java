package ua.highload.shop.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.highload.shop.api.Dtos.CartItem;
import ua.highload.shop.api.Dtos.CartItemRequest;
import ua.highload.shop.api.Dtos.CartResponse;
import ua.highload.shop.api.Dtos.CheckoutRequest;
import ua.highload.shop.domain.Order;
import ua.highload.shop.service.AuthService;
import ua.highload.shop.service.CartService;
import ua.highload.shop.service.OrderService;

@RestController
@RequestMapping("/api")
public class OrderController {

    private static final String AUTH = "Authorization";

    private final AuthService auth;
    private final CartService carts;
    private final OrderService orders;

    public OrderController(AuthService auth, CartService carts, OrderService orders) {
        this.auth = auth;
        this.carts = carts;
        this.orders = orders;
    }

    @GetMapping("/cart")
    public CartResponse cart(@RequestHeader(value = AUTH, required = false) String authorization) {
        long userId = auth.requireUser(authorization);
        return toResponse(userId, carts.get(userId));
    }

    @PostMapping("/cart/items")
    public CartResponse addToCart(@RequestHeader(value = AUTH, required = false) String authorization,
                                  @Valid @RequestBody CartItemRequest req) {
        long userId = auth.requireUser(authorization);
        return toResponse(userId, carts.add(userId, req.productId(), req.quantity()));
    }

    @DeleteMapping("/cart/items/{productId}")
    public CartResponse removeFromCart(@RequestHeader(value = AUTH, required = false) String authorization,
                                       @PathVariable long productId) {
        long userId = auth.requireUser(authorization);
        return toResponse(userId, carts.remove(userId, productId));
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Order checkout(@RequestHeader(value = AUTH, required = false) String authorization,
                          @Valid @RequestBody(required = false) CheckoutRequest req) {
        long userId = auth.requireUser(authorization);
        boolean fromCart = req == null || req.items() == null || req.items().isEmpty();
        Map<Long, Integer> items = new LinkedHashMap<>();
        if (fromCart) {
            items.putAll(carts.get(userId));
        } else {
            req.items().forEach(i -> items.merge(i.productId(), i.quantity(), Integer::sum));
        }
        Order order = orders.checkout(userId, items);
        if (fromCart) {
            carts.clear(userId);
        }
        return order;
    }

    @GetMapping("/orders/{id}")
    public Order get(@RequestHeader(value = AUTH, required = false) String authorization, @PathVariable long id) {
        return orders.get(auth.requireUser(authorization), id);
    }

    @GetMapping("/orders/my")
    public List<Order> my(@RequestHeader(value = AUTH, required = false) String authorization,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return orders.byUser(auth.requireUser(authorization), limit);
    }

    private static CartResponse toResponse(long userId, Map<Long, Integer> cart) {
        return new CartResponse(userId, cart.entrySet().stream()
                .map(e -> new CartItem(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparingLong(CartItem::productId))
                .toList());
    }
}
