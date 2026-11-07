package io.cloudforge.identity.application.port;

import io.cloudforge.identity.domain.User;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface TokenProvider {

    String generateAccessToken(User user);

    String generateRefreshToken(User user);

    Duration getRefreshTokenTtl();

    /** Checks the signed token and its type, not its Redis entry or account status. */
    boolean isValidRefreshToken(String token);

    /** Returns the subject of a valid access token. The caller must check account status. */
    Optional<UUID> validateAccessToken(String token);

}