package io.cloudforge.identity.application.port;

import io.cloudforge.identity.domain.User;

import java.time.Duration;

public interface TokenProvider {

    String generateAccessToken(User user);

    String generateRefreshToken(User user);

    Duration getRefreshTokenTtl();

}