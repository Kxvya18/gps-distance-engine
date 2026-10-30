package com.moveinsync.distance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

// Demo users. A real deployment would use the client's identity provider.
// Passwords are kept only as bcrypt hashes.
@Component
public class UserDirectory {

    public record User(String userId, String tenantId, String role, String passwordHash) {
    }

    private final Map<String, User> users;
    private final PasswordEncoder encoder;

    public UserDirectory(PasswordEncoder encoder, @Value("${app.demo-password}") String demoPassword) {
        this.encoder = encoder;
        String hash = encoder.encode(demoPassword);
        this.users = Map.of(
                "admin-a", new User("admin-a", "client-a", "ADMIN", hash),
                "operator-a", new User("operator-a", "client-a", "OPERATOR", hash),
                "emp-1", new User("emp-1", "client-a", "EMPLOYEE", hash),
                "admin-b", new User("admin-b", "client-b", "ADMIN", hash));
    }

    public Optional<User> check(String userId, String password) {
        User user = users.get(userId);
        if (user == null || !encoder.matches(password, user.passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
