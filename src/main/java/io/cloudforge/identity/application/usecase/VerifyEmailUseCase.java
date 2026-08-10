package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.VerifyEmailCommand;
import io.cloudforge.identity.application.port.*;
import io.cloudforge.identity.domain.User;
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

        User user = userRepository.findByEmail(email)
                .orElseThrow(
                        () -> new UserNotFoundException(email)
                );

        if (user.isActive()) {
            throw new UserAlreadyActiveException(email);
        }

        if (otpStore.isVerificationBlocked(email)) {
            throw new VerificationBlockedException(email);
        }

        String storedOtpHash = otpStore.getOtpHash(email)
                .orElseThrow(InvalidOtpException::new);

        String inputOtpHash = hashProvider.hash(rawOtp);

        if (!storedOtpHash.equals(inputOtpHash)) {
            handleFailedAttempt(email);
            throw new InvalidOtpException();
        }

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

        otpStore.delete(email);

        return new VerifyEmailResult(
                accessToken,
                refreshToken
        );
    }

    private void handleFailedAttempt(String email) {

        long attempts =
                otpStore.incrementFailedAttempts(
                        email,
                        ATTEMPTS_TTL
                );

        if (attempts >= MAX_FAILED_ATTEMPTS) {

            otpStore.blockVerification(
                    email,
                    BLOCK_DURATION
            );

            throw new VerificationBlockedException(email);
        }
    }

    public record VerifyEmailResult(
            String accessToken,
            String refreshToken
    ) {
    }
}