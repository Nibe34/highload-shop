package ua.highload.shop.domain;

import java.time.Instant;

public record Product(long id, long categoryId, String name, String description,
                      long priceCents, int stock, Instant createdAt, Instant updatedAt) {
}
