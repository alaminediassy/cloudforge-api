package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.SendActivationOtpCommand;
import io.cloudforge.identity.application.port.EmailProvider;
import io.cloudforge.identity.application.port.OtpGenerator;
import io.cloudforge.identity.application.port.OtpStore;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.OtpCooldownException;
import io.cloudforge.identity.domain.exception.UserAlreadyActiveException;
import io.cloudforge.identity.domain.exception.UserNotFoundException;
import io.cloudforge.identity.domain.exception.VerificationBlockedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

@Service
@Transactional
@RequiredArgsConstructor
public class SendActivationOtpUseCase {

    private static final Duration OTP_TTL =
            Duration.ofMinutes(7);

    private static final Duration COOLDOWN_TTL =
            Duration.ofMinutes(2);

    private final UserRepository userRepository;
    private final OtpGenerator otpGenerator;
    private final OtpStore otpStore;
    private final EmailProvider emailProvider;

    public void send(SendActivationOtpCommand command) {

        String email = command.email();

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

        String otp = otpGenerator.generate();

        String otpHash = hashSha256(otp);

        boolean allowed = otpStore.saveIfAllowed(
                email,
                otpHash,
                OTP_TTL,
                COOLDOWN_TTL
        );

        if (!allowed) {
            throw new OtpCooldownException(email);
        }

        emailProvider.sendActivationEmail(email, otp);
    }

    private String hashSha256(String value) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] encodedHash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder hexString =
                    new StringBuilder(2 * encodedHash.length);

            for (byte b : encodedHash) {

                String hex =
                        Integer.toHexString(0xff & b);

                if (hex.length() == 1) {
                    hexString.append('0');
                }

                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "SHA-256 algorithm not available",
                    e
            );
        }
    }
}