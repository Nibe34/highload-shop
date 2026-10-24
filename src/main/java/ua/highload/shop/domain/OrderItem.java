package ua.highload.shop.domain;

public record OrderItem(long productId, int quantity, long priceCents) {
}
