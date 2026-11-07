package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.VerifyEmailCommand;
import io.cloudforge.identity.application.port.*;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.domain.exception.InvalidUserStateException;
import io.cloudforge.identity.domain.exception.InvalidOtpException;
import io.cloudforge.identity.domain.exception.UserAlreadyActiveException;
import io.cloudforge.identity.domain.exception.UserNotFoundException;
import io.cloudforge.identity.domain.exception.VerificationBlockedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@Transactional
@RequiredArgsConstructor
public class VerifyEmailUseCase {

    private static final int MAX_FAILED_ATTEMPTS = 3;

    private static final Duration BLOCK_DURATION = Duration.ofMinutes(15);

    private static final Duration ATTEMPTS_TTL = Duration.ofMinutes(7);

    private final UserRepository userRepository;
    private final OtpStore otpStore;
    private final TokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final HashProvider hashProvider;

    public VerifyEmailResult verify(VerifyEmailCommand command) {

        String email = command.email();
        String rawOtp = command.otp();

        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(
                        () -> new UserNotFoundException(email)
                );

        if (user.isActive()) {
            throw new UserAlreadyActiveException(email);
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new InvalidUserStateException();
        }

        OtpStore.VerificationResult result = otpStore.verifyAndConsume(email, hashProvider.hash(rawOtp),
                MAX_FAILED_ATTEMPTS, ATTEMPTS_TTL, BLOCK_DURATION);
        if (result == OtpStore.VerificationResult.BLOCKED) {
            throw new VerificationBlockedException(email);
        }
        if (result != OtpStore.VerificationResult.ACCEPTED) {
            throw new InvalidOtpException();
        }

        // Redis is not part of the SQL transaction.
        // A SQL rollback does not restore the consumed code.
        user.activate();
        userRepository.save(user);

        String accessToken =
                tokenProvider.generateAccessToken(user);

        String refreshToken =
                tokenProvider.generateRefreshToken(user);

        String refreshTokenHash =
                hashProvider.hash(refreshToken);

        refreshTokenStore.save(
                refreshTokenHash,
                user.getId(),
                tokenProvider.getRefreshTokenTtl()
        );

        return new VerifyEmailResult(
                accessToken,
                refreshToken
        );
    }

    public record VerifyEmailResult(
            String accessToken,
            String refreshToken
    ) {
    }
}