package ua.highload.shop.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Вхідні DTO REST API. */
public final class Dtos {

    private Dtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Size(max = 64) String region,
            @NotBlank @Size(min = 6, max = 128) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record LoginResponse(String token, long userId) {
    }

    public record ProductRequest(
            @NotNull @Positive Long categoryId,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10_000) String description,
            @NotNull @PositiveOrZero Long priceCents,
            @NotNull @PositiveOrZero Integer stock) {
    }

    public record CartItemRequest(@NotNull @Positive Long productId, @NotNull @Min(1) @Max(100) Integer quantity) {
    }

    public record CartItem(long productId, int quantity) {
    }

    public record CartResponse(long userId, List<CartItem> items) {
    }

    /** Якщо items порожній/відсутній — замовлення формується з кошика. */
    public record CheckoutRequest(@Valid @Size(max = 50) List<CartItemRequest> items) {
    }

    public record SummaryResponse(long ordersCreatedSinceStart, String instance) {
    }
}
