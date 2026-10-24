package ua.highload.shop.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ua.highload.shop.domain.ConflictException;
import ua.highload.shop.domain.User;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public User create(String email, String fullName, String region, String passwordHash) {
        var params = new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("fullName", fullName)
                .addValue("region", region)
                .addValue("hash", passwordHash);
        try {
            return jdbc.queryForObject("""
                    INSERT INTO users (email, full_name, region, password_hash)
                    VALUES (:email, :fullName, :region, :hash)
                    RETURNING id, email, full_name, region, created_at
                    """, params, UserRepository::map);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Email already registered: " + email);
        }
    }

    public Optional<User> findById(long id) {
        return jdbc.query("SELECT id, email, full_name, region, created_at FROM users WHERE id = :id",
                new MapSqlParameterSource("id", id), UserRepository::map).stream().findFirst();
    }

    /** Повертає пару (userId, passwordHash) для перевірки логіну. */
    public Optional<Credentials> findCredentials(String email) {
        return jdbc.query("SELECT id, password_hash FROM users WHERE email = :email",
                new MapSqlParameterSource("email", email),
                (rs, i) -> new Credentials(rs.getLong("id"), rs.getString("password_hash"))).stream().findFirst();
    }

    static User map(ResultSet rs, int rowNum) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"),
                rs.getString("region"), rs.getTimestamp("created_at").toInstant());
    }

    public record Credentials(long userId, String passwordHash) {
    }
}
