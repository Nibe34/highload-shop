package ua.highload.shop.cache;

public record Cached<T>(T value, CacheStatus status) {
}
