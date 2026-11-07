package io.cloudforge.identity.infrastructure.security;

import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.domain.User;
import io.cloudforge.shared.configuration.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtTokenProvider implements TokenProvider {
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "ACCESS";
    private static final String TYPE_REFRESH = "REFRESH";

    private final JwtProperties properties;
    private final SecretKey key;
    private final MacAlgorithm algorithm;
    private final JwtParser accessParser;
    private final JwtParser refreshParser;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        // Use the same algorithm to sign and validate tokens.
        this.algorithm = switch (key.getAlgorithm()) {
            case "HmacSHA512" -> Jwts.SIG.HS512;
            case "HmacSHA384" -> Jwts.SIG.HS384;
            default -> Jwts.SIG.HS256;
        };
        this.accessParser = parser(TYPE_ACCESS);
        this.refreshParser = parser(TYPE_REFRESH);
    }

    private JwtParser parser(String type) {
        return Jwts.parser().verifyWith(key).requireIssuer(properties.issuer()).require(CLAIM_TYPE, type).build();
    }

    @Override
    public String generateAccessToken(User user) {
        return generateToken(user, properties.accessTokenExpiration(), TYPE_ACCESS);
    }

    @Override
    public String generateRefreshToken(User user) {
        return generateToken(user, properties.refreshTokenExpiration(), TYPE_REFRESH);
    }

    @Override
    public Duration getRefreshTokenTtl() {
        return properties.refreshTokenExpiration();
    }

    @Override
    public Optional<UUID> validateAccessToken(String token) {
        return validate(token, accessParser);
    }

    @Override
    public boolean isValidRefreshToken(String token) {
        return validate(token, refreshParser).isPresent();
    }

    private Optional<UUID> validate(String token, JwtParser parser) {
        try {
            var signed = parser.parseSignedClaims(token);
            // Reject other algorithms, even if they could verify with the same key.
            if (!algorithm.getId().equals(signed.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            Claims claims = signed.getPayload();
            if (claims.getExpiration() == null || claims.getSubject() == null) {
                return Optional.empty();
            }
            UUID userId = UUID.fromString(claims.getSubject());
            if (!userId.toString().equalsIgnoreCase(claims.getSubject())) {
                return Optional.empty();
            }
            return Optional.of(userId);
        } catch (JwtException | IllegalArgumentException ex) {
            // Parser errors may contain token data. Do not log them.
            return Optional.empty();
        }
    }

    private String generateToken(User user, Duration lifetime, String type) {
        Instant now = Instant.now();
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(user.getId().toString())
                .claim("email", user.getEmail()).claim(CLAIM_TYPE, type).issuer(properties.issuer())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(lifetime)))
                .signWith(key, algorithm).compact();
    }
}
