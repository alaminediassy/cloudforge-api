package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.SendActivationOtpCommand;
import io.cloudforge.identity.application.port.OtpGenerator;
import io.cloudforge.identity.application.port.OtpStore;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.domain.exception.InvalidUserStateException;
import io.cloudforge.identity.domain.exception.OtpCooldownException;
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
public class PrepareActivationOtpUseCase {

    private static final Duration OTP_TTL =
            Duration.ofMinutes(7);

    private static final Duration COOLDOWN_TTL =
            Duration.ofMinutes(2);

    private final UserRepository userRepository;
    private final OtpGenerator otpGenerator;
    private final OtpStore otpStore;
    private final HashProvider hashProvider;

    public String prepare(SendActivationOtpCommand command) {

        String email = command.email();

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

        String otp = otpGenerator.generate();

        String otpHash = hashProvider.hash(otp);

        OtpStore.IssueResult result = otpStore.issue(
                email,
                otpHash,
                OTP_TTL,
                COOLDOWN_TTL
        );

        if (result == OtpStore.IssueResult.BLOCKED) {
            throw new VerificationBlockedException(email);
        }
        if (result == OtpStore.IssueResult.COOLDOWN) {
            throw new OtpCooldownException(email);
        }

        return otp;
    }

}