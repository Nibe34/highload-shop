package ua.highload.shop.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/** Формат: salt$hex(sha256(salt$password)). Навчальна реалізація — у продакшні варто брати bcrypt/argon2. */
@Component
public class PasswordHasher {

    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] saltBytes = new byte[8];
        random.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return salt + "$" + digest(salt + "$" + password);
    }

    public boolean matches(String password, String stored) {
        int sep = stored.indexOf('$');
        if (sep < 0) {
            return false;
        }
        String salt = stored.substring(0, sep);
        return MessageDigest.isEqual(stored.substring(sep + 1).getBytes(StandardCharsets.UTF_8),
                digest(salt + "$" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String value) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
