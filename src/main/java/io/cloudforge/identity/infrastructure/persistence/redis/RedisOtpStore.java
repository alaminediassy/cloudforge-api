package io.cloudforge.identity.infrastructure.persistence.redis;

import io.cloudforge.identity.application.port.OtpStore;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisOtpStore implements OtpStore {
    private static final DefaultRedisScript<Long> ISSUE = script("redis/issue-otp.lua");
    private static final DefaultRedisScript<Long> VERIFY = script("redis/verify-otp.lua");
    private final StringRedisTemplate redisTemplate;

    @Override
    public IssueResult issue(String email, String otpHash, Duration otpTtl, Duration cooldownTtl) {
        Long result = redisTemplate.execute(ISSUE, keys(email), otpHash, millis(otpTtl), millis(cooldownTtl));
        if (Long.valueOf(1).equals(result)) return IssueResult.STORED;
        if (Long.valueOf(2).equals(result)) return IssueResult.BLOCKED;
        if (Long.valueOf(0).equals(result)) return IssueResult.COOLDOWN;
        throw new IllegalStateException("Unexpected OTP storage result");
    }

    @Override
    public VerificationResult verifyAndConsume(String email, String otpHash, int maxAttempts,
                                               Duration attemptsTtl, Duration blockTtl) {
        if (maxAttempts < 1) throw new IllegalArgumentException("Attempts must be positive");
        Long result = redisTemplate.execute(VERIFY, keys(email), otpHash, Integer.toString(maxAttempts),
                millis(attemptsTtl), millis(blockTtl));
        if (Long.valueOf(1).equals(result)) return VerificationResult.ACCEPTED;
        if (Long.valueOf(2).equals(result)) return VerificationResult.BLOCKED;
        if (Long.valueOf(0).equals(result)) return VerificationResult.INVALID;
        throw new IllegalStateException("Unexpected OTP verification result");
    }

    // These multi-key scripts require standalone Redis, not Redis Cluster.
    private static List<String> keys(String email) {
        return List.of("cloudforge:otp:hash:" + email, "cloudforge:otp:cooldown:" + email,
                "cloudforge:otp:attempts:" + email, "cloudforge:otp:blocked:" + email);
    }

    private static String millis(Duration duration) {
        if (duration == null || duration.toMillis() < 1) {
            throw new IllegalArgumentException("OTP durations must be positive");
        }
        return Long.toString(duration.toMillis());
    }

    private static DefaultRedisScript<Long> script(String path) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
