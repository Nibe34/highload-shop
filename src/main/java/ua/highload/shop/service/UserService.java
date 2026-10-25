package ua.highload.shop.service;

import org.springframework.stereotype.Service;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.domain.NotFoundException;
import ua.highload.shop.domain.User;
import ua.highload.shop.repo.UserRepository;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordHasher hasher;

    public UserService(UserRepository users, PasswordHasher hasher) {
        this.users = users;
        this.hasher = hasher;
    }

    public User register(String email, String fullName, String region, String password) {
        return users.create(email.toLowerCase(), fullName, region, hasher.hash(password));
    }

    /** Публічний профіль — Eventual Consistency, читання з Replica. */
    public User get(long id) {
        return DbRouting.replica(() -> users.findById(id)).orElseThrow(() -> notFound(id));
    }

    /** Власний профіль — Strong: користувач щойно міг зареєструватися, читаємо з Primary. */
    public User own(long id) {
        return DbRouting.primary(() -> users.findById(id)).orElseThrow(() -> notFound(id));
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("User " + id + " not found");
    }
}
