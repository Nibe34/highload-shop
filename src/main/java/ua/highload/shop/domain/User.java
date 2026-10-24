package ua.highload.shop.domain;

import java.time.Instant;

public record User(long id, String email, String fullName, String region, Instant createdAt) {
}
