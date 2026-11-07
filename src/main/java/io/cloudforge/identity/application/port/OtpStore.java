package io.cloudforge.identity.application.port;

import java.time.Duration;

public interface OtpStore {
    enum IssueResult { STORED, COOLDOWN, BLOCKED }
    enum VerificationResult { ACCEPTED, INVALID, BLOCKED }

    /**
     * Stores a code hash and cooldown together unless issuance is blocked.
     * Keeps the current failed-attempt window when a code is replaced.
     */
    IssueResult issue(String email, String otpHash, Duration otpTtl, Duration cooldownTtl);

    /**
     * Checks and consumes a valid code in one atomic operation.
     * Counts wrong codes and blocks further attempts at the limit.
     * Two requests must not both consume the same code.
     */
    VerificationResult verifyAndConsume(String email, String otpHash, int maxAttempts,
                                        Duration attemptsTtl, Duration blockTtl);
}
