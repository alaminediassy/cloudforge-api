package io.cloudforge.identity.application.port;

import java.time.Duration;
import java.util.Optional;

public interface OtpStore {

    boolean saveIfAllowed(
            String email,
            String otpHash,
            Duration otpTtl,
            Duration cooldownTtl
    );

    Optional<String> getOtpHash(String email);

    long incrementFailedAttempts(String email, Duration ttl);

    void blockVerification(String email, Duration ttl);

    boolean isVerificationBlocked(String email);

    void delete(String email);
}
