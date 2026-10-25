package ua.highload.shop.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.domain.UnauthorizedException;
import ua.highload.shop.repo.UserRepository;

/**
 * Сесії зберігаються у Redis (ключ {@code session:<token>} → userId) з ковзним TTL,
 * тому будь-який інстанс може автентифікувати будь-який запит.
 */
@Service
public class AuthService {

    private static final String PREFIX = "session:";

    private final StringRedisTemplate redis;
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final Duration ttl;

    public AuthService(StringRedisTemplate redis, UserRepository users, PasswordHasher hasher,
                       @Value("${shop.session-ttl:30m}") Duration ttl) {
        this.redis = redis;
        this.users = users;
        this.hasher = hasher;
        this.ttl = ttl;
    }

    public Session login(String email, String password) {
        // Автентифікація — Strictly Consistent: щойно зареєстрований користувач має одразу увійти.
        var creds = DbRouting.primary(() -> users.findCredentials(email.toLowerCase()))
                .filter(c -> hasher.matches(password, c.passwordHash()))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + token, Long.toString(creds.userId()), ttl);
        return new Session(token, creds.userId());
    }

    public void logout(String token) {
        redis.delete(PREFIX + token);
    }

    /** Розбирає заголовок "Authorization: Bearer <token>", продовжує TTL сесії (GETEX) і повертає id користувача. */
    public long requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new UnauthorizedException("Missing bearer token");
        }
        String userId = redis.opsForValue().getAndExpire(PREFIX + authorization.substring(7).trim(), ttl);
        if (userId == null) {
            throw new UnauthorizedException("Session expired or unknown");
        }
        return Long.parseLong(userId);
    }

    public record Session(String token, long userId) {
    }
}
