package io.cloudforge.identity.infrastructure.security;

import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.domain.User;
import io.cloudforge.shared.configuration.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider implements TokenProvider {

    private final JwtProperties jwtProperties;

    @Override
    public String generateAccessToken(User user) {
        return generateToken(
                user,
                jwtProperties.accessTokenExpiration()
        );
    }

    @Override
    public String generateRefreshToken(User user) {
        return generateToken(
                user,
                jwtProperties.refreshTokenExpiration()
        );
    }

    @Override
    public Duration getRefreshTokenTtl() {
        return jwtProperties.refreshTokenExpiration();
    }

    private String generateToken(
            User user,
            java.time.Duration expiration
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(
                jwtProperties.secret()
                        .getBytes(StandardCharsets.UTF_8)
        );
    }
}