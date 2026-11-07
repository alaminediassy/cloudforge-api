package io.cloudforge.identity.application.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {

    void save(String tokenHash, UUID userId, Duration ttl);

    boolean exists(String tokenHash);

    void revoke(String tokenHash);

    /**
     * Removes and returns the token owner in one atomic operation.
     *
     * Two requests using the same token must not both succeed.
     */
    Optional<UUID> consume(String tokenHash);
}
