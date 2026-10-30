package com.moveinsync.distance.controller;

import com.moveinsync.distance.security.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

// Exchanges a user id and password for a signed token.
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    public record LoginRequest(String userId, String password) {
    }

    public record LoginResponse(String token) {
    }

    private final UserDirectory users;
    private final JwtEncoder encoder;

    public AuthController(UserDirectory users, JwtEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        UserDirectory.User user = users.check(request.userId(), request.password())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong user or password"));

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.userId())
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_LIFETIME))
                .claim("tenant", user.tenantId())
                .claim("role", user.role())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new LoginResponse(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue());
    }
}
