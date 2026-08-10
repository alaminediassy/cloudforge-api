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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SendActivationOtpUseCaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OtpGenerator otpGenerator;

    @Mock
    private OtpStore otpStore;

    @Mock
    private EmailProvider emailProvider;

    @Mock
    private User user;

    private SendActivationOtpUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SendActivationOtpUseCase(
                userRepository,
                otpGenerator,
                otpStore,
                emailProvider
        );
    }

    @Test
    void shouldSendOtpSuccessfullyAndEnsureSha256Hashing() {

        SendActivationOtpCommand command =
                new SendActivationOtpCommand(
                        "test@cloudforge.io"
                );

        String generatedOtp = "123456";

        String expectedHash =
                "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92";

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(otpStore.isVerificationBlocked(command.email()))
                .thenReturn(false);

        when(otpGenerator.generate())
                .thenReturn(generatedOtp);

        when(
                otpStore.saveIfAllowed(
                        eq(command.email()),
                        eq(expectedHash),
                        eq(Duration.ofMinutes(7)),
                        eq(Duration.ofMinutes(2))
                )
        ).thenReturn(true);

        useCase.send(command);

        verify(otpStore).saveIfAllowed(
                eq(command.email()),
                eq(expectedHash),
                eq(Duration.ofMinutes(7)),
                eq(Duration.ofMinutes(2))
        );

        verify(emailProvider).sendActivationEmail(
                command.email(),
                generatedOtp
        );
    }

    @Test
    void shouldRejectUnknownUser() {

        SendActivationOtpCommand command =
                new SendActivationOtpCommand(
                        "unknown@cloudforge.io"
                );

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.empty());

        assertThrows(
                UserNotFoundException.class,
                () -> useCase.send(command)
        );

        verifyNoInteractions(
                otpGenerator,
                otpStore,
                emailProvider
        );
    }

    @Test
    void shouldRejectAlreadyActiveUser() {

        SendActivationOtpCommand command =
                new SendActivationOtpCommand(
                        "active@cloudforge.io"
                );

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(true);

        assertThrows(
                UserAlreadyActiveException.class,
                () -> useCase.send(command)
        );

        verifyNoInteractions(
                otpGenerator,
                otpStore,
                emailProvider
        );
    }

    @Test
    void shouldRejectBlockedUser() {

        SendActivationOtpCommand command =
                new SendActivationOtpCommand(
                        "blocked@cloudforge.io"
                );

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(otpStore.isVerificationBlocked(command.email()))
                .thenReturn(true);

        assertThrows(
                VerificationBlockedException.class,
                () -> useCase.send(command)
        );

        verify(
                otpStore,
                never()
        ).saveIfAllowed(
                any(),
                any(),
                any(),
                any()
        );

        verifyNoInteractions(
                otpGenerator,
                emailProvider
        );
    }

    @Test
    void shouldRejectWhenCooldownActive() {

        SendActivationOtpCommand command =
                new SendActivationOtpCommand(
                        "cooldown@cloudforge.io"
                );

        String generatedOtp = "123456";

        String expectedHash =
                "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92";

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(otpStore.isVerificationBlocked(command.email()))
                .thenReturn(false);

        when(otpGenerator.generate())
                .thenReturn(generatedOtp);

        when(
                otpStore.saveIfAllowed(
                        eq(command.email()),
                        eq(expectedHash),
                        eq(Duration.ofMinutes(7)),
                        eq(Duration.ofMinutes(2))
                )
        ).thenReturn(false);

        assertThrows(
                OtpCooldownException.class,
                () -> useCase.send(command)
        );

        verify(
                emailProvider,
                never()
        ).sendActivationEmail(
                any(),
                any()
        );
    }
}