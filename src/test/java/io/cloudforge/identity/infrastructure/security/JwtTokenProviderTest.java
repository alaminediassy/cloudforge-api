package io.cloudforge.identity.infrastructure.security;

import io.cloudforge.identity.domain.User;
import io.cloudforge.shared.configuration.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    @ParameterizedTest
    @CsvSource({"32,HS256", "48,HS384", "64,HS512"})
    void preservesExistingAlgorithmAndClaimsWhileSeparatingTokenTypes(int bytes, String algorithm) {
        String secret = "s".repeat(bytes);
        var properties = new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), "cloudforge-api");
        var provider = new JwtTokenProvider(properties);
        User user = User.create(UUID.randomUUID(), "test@example.org", "First", "Last", "hash");
        String access = provider.generateAccessToken(user);
        String refresh = provider.generateRefreshToken(user);
        var parser = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).build();
        var parsedAccess = parser.parseSignedClaims(access);
        var parsedRefresh = parser.parseSignedClaims(refresh);
        assertEquals(algorithm, parsedAccess.getHeader().getAlgorithm());
        assertEquals("ACCESS", parsedAccess.getPayload().get("type"));
        assertEquals("REFRESH", parsedRefresh.getPayload().get("type"));
        assertEquals(user.getId().toString(), parsedAccess.getPayload().getSubject());
        assertEquals(user.getEmail(), parsedAccess.getPayload().get("email"));
        assertEquals("cloudforge-api", parsedAccess.getPayload().getIssuer());
        assertNotEquals(parsedAccess.getPayload().getId(), parsedRefresh.getPayload().getId());
        assertEquals(Duration.ofMinutes(15).toMillis(), parsedAccess.getPayload().getExpiration().getTime()
                - parsedAccess.getPayload().getIssuedAt().getTime());
        assertEquals(user.getId(), provider.validateAccessToken(access).orElseThrow());
        assertTrue(provider.isValidRefreshToken(refresh));
        assertFalse(provider.isValidRefreshToken(access));
        assertTrue(provider.validateAccessToken(refresh).isEmpty());
        assertEquals(Duration.ofDays(7), provider.getRefreshTokenTtl());
    }

    @Test void rejectsIncompleteRefreshTokensAndMalformedInput() {
        String secret = "s".repeat(32);
        var provider = new JwtTokenProvider(new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), "cloudforge-api"));
        var key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String noExpiration = Jwts.builder().subject(UUID.randomUUID().toString()).issuer("cloudforge-api")
                .claim("type", "REFRESH").signWith(key).compact();
        String noSubject = Jwts.builder().issuer("cloudforge-api").claim("type", "REFRESH")
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        assertFalse(provider.isValidRefreshToken(noExpiration));
        assertFalse(provider.isValidRefreshToken(noSubject));
        assertFalse(provider.isValidRefreshToken("malformed"));
        assertTrue(provider.validateAccessToken(null).isEmpty());
    }
}
