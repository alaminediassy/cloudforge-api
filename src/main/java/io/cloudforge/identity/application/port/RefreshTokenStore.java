package io.cloudforge.identity.application.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {

    void save(String tokenHash, UUID userId, Duration ttl);

    boolean exists(String tokenHash);

    void revoke(String tokenHash);

    Optional<UUID> consume(String tokenHash);
}
