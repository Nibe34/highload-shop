package ua.highload.shop.domain;

import java.time.Instant;
import java.util.List;

public record Order(long id, long userId, String status, long totalCents, Instant createdAt, List<OrderItem> items) {

    public Order withItems(List<OrderItem> newItems) {
        return new Order(id, userId, status, totalCents, createdAt, newItems);
    }
}
