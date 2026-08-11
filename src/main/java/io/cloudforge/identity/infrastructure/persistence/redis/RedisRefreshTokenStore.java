package io.cloudforge.identity.infrastructure.persistence.redis;

import io.cloudforge.identity.application.port.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "cloudforge:refresh-token:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String tokenHash, UUID userId, Duration ttl) {
        redisTemplate.opsForValue()
                .set(buildKey(tokenHash), userId.toString(), ttl);
    }

    @Override
    public boolean exists(String tokenHash) {
        return redisTemplate.hasKey(buildKey(tokenHash));
    }

    @Override
    public void revoke(String tokenHash) {
        redisTemplate.delete(buildKey(tokenHash));
    }

    @Override
    public Optional<UUID> consume(String tokenHash) {
        String userIdStr = redisTemplate.opsForValue()
                .getAndDelete(buildKey(tokenHash));

        return Optional.ofNullable(userIdStr)
                .map(UUID::fromString);
    }

    private String buildKey(String tokenHash) {
        return KEY_PREFIX + tokenHash;
    }
}
