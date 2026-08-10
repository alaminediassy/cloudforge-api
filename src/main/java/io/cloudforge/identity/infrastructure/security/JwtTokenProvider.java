package io.cloudforge.identity.infrastructure.security;

import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.domain.User;
import io.cloudforge.shared.configuration.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider implements TokenProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "ACCESS";
    private static final String TYPE_REFRESH = "REFRESH";

    private final JwtProperties jwtProperties;

    @Override
    public String generateAccessToken(User user) {
        return generateToken(
                user,
                jwtProperties.accessTokenExpiration(),
                TYPE_ACCESS
        );
    }

    @Override
    public String generateRefreshToken(User user) {
        return generateToken(
                user,
                jwtProperties.refreshTokenExpiration(),
                TYPE_REFRESH
        );
    }

    @Override
    public Duration getRefreshTokenTtl() {
        return jwtProperties.refreshTokenExpiration();
    }

    @Override
    public boolean isValidRefreshToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(jwtProperties.issuer())
                    .require(CLAIM_TYPE, TYPE_REFRESH)
                    .build()
                    .parseSignedClaims(token);

            return true;

        } catch (JwtException | IllegalArgumentException e) {
            log.debug(
                    "Validation du Refresh Token échouée: {}",
                    e.getMessage()
            );
            return false;
        }
    }

    private String generateToken(
            User user,
            java.time.Duration expiration,
            String tokenType
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim(CLAIM_TYPE, tokenType)
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