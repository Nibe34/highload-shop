package ua.highload.shop.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import ua.highload.shop.domain.UnauthorizedException;
import ua.highload.shop.repo.UserRepository;

/** Сесії зберігаються в пам'яті процесу (MVP). */
@Service
public class AuthService {

    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final UserRepository users;
    private final PasswordHasher hasher;

    public AuthService(UserRepository users, PasswordHasher hasher) {
        this.users = users;
        this.hasher = hasher;
    }

    public Session login(String email, String password) {
        var creds = users.findCredentials(email.toLowerCase())
                .filter(c -> hasher.matches(password, c.passwordHash()))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        String token = UUID.randomUUID().toString();
        sessions.put(token, creds.userId());
        return new Session(token, creds.userId());
    }

    public void logout(String token) {
        sessions.remove(token);
    }

    /** Розбирає заголовок "Authorization: Bearer <token>" і повертає id користувача. */
    public long requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new UnauthorizedException("Missing bearer token");
        }
        Long userId = sessions.get(authorization.substring(7).trim());
        if (userId == null) {
            throw new UnauthorizedException("Session expired or unknown");
        }
        return userId;
    }

    public record Session(String token, long userId) {
    }
}
