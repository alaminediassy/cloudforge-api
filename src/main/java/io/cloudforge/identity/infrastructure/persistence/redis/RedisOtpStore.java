package io.cloudforge.identity.infrastructure.persistence.redis;

import io.cloudforge.identity.application.port.OtpStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisOtpStore implements OtpStore {

    private static final String HASH_KEY_PREFIX = "cloudforge:otp:hash:";
    private static final String COOLDOWN_KEY_PREFIX = "cloudforge:otp:cooldown:";
    private static final String ATTEMPTS_KEY_PREFIX = "cloudforge:otp:attempts:";
    private static final String BLOCKED_KEY_PREFIX = "cloudforge:otp:blocked:";

    private final StringRedisTemplate redisTemplate;


    @Override
    public boolean saveIfAllowed(String email, String otpHash, Duration otpTtl, Duration cooldownTtl) {

        String blockedKey = BLOCKED_KEY_PREFIX + email;
        if (redisTemplate.hasKey(blockedKey)) {
            return false;
        }

        String cooldownKey = COOLDOWN_KEY_PREFIX + email;
        Boolean allowed = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", cooldownTtl);

        if (Boolean.TRUE.equals(allowed)) {
            String hashKey = HASH_KEY_PREFIX + email;
            String attemptsKey = ATTEMPTS_KEY_PREFIX + email;

            redisTemplate.opsForValue().set(hashKey, otpHash, otpTtl);

            redisTemplate.delete(attemptsKey);

            return true;
        }

        return false;
    }

    @Override
    public Optional<String> getOtpHash(String email) {
        String hashKey = HASH_KEY_PREFIX + email;
        return Optional.ofNullable(redisTemplate.opsForValue().get(hashKey));
    }

    @Override
    public long incrementFailedAttempts(String email, Duration ttl) {
        String attemptsKey = ATTEMPTS_KEY_PREFIX + email;

        Long attempts = redisTemplate.opsForValue().increment(attemptsKey);

        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(attemptsKey, ttl);
        }

        return attempts != null ? attempts : 0L;
    }

    @Override
    public void blockVerification(String email, Duration ttl) {
        String blockedKey = BLOCKED_KEY_PREFIX + email;
        redisTemplate.opsForValue().set(blockedKey, "1", ttl);
    }

    @Override
    public boolean isVerificationBlocked(String email) {
        String blockedKey = BLOCKED_KEY_PREFIX + email;
        return redisTemplate.hasKey(blockedKey);
    }

    @Override
    public void delete(String email) {
        redisTemplate.delete(Arrays.asList(
                HASH_KEY_PREFIX + email,
                COOLDOWN_KEY_PREFIX + email,
                ATTEMPTS_KEY_PREFIX + email,
                BLOCKED_KEY_PREFIX + email
        ));
    }
}
