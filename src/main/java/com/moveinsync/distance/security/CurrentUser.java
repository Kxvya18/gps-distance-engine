package com.moveinsync.distance.security;

import org.springframework.security.oauth2.jwt.Jwt;

// Who is calling. The tenant comes from the signed token, never from the request body.
public record CurrentUser(String userId, String tenantId, String role) {

    public static CurrentUser from(Jwt token) {
        return new CurrentUser(token.getSubject(), token.getClaimAsString("tenant"), token.getClaimAsString("role"));
    }

    public boolean isEmployee() {
        return "EMPLOYEE".equals(role);
    }
}
