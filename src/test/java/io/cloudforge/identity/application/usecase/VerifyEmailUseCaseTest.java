package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.VerifyEmailCommand;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.application.port.OtpStore;
import io.cloudforge.identity.application.port.RefreshTokenStore;
import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.InvalidOtpException;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerifyEmailUseCaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OtpStore otpStore;

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private HashProvider hashProvider;

    @Mock
    private User user;

    private VerifyEmailUseCase useCase;

    private final String email = "test@cloudforge.io";
    private final String rawOtp = "123456";
    private final String otpHash = "hashed-otp";

    @BeforeEach
    void setUp() {
        useCase = new VerifyEmailUseCase(
                userRepository,
                otpStore,
                tokenProvider,
                refreshTokenStore,
                hashProvider
        );
    }

    @Test
    void shouldVerifyAndActivateSuccessfully() {

        VerifyEmailCommand command =
                new VerifyEmailCommand(email, rawOtp);

        UUID userId = UUID.randomUUID();

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(user.getId())
                .thenReturn(userId);

        when(otpStore.isVerificationBlocked(email))
                .thenReturn(false);

        when(otpStore.getOtpHash(email))
                .thenReturn(Optional.of(otpHash));

        when(hashProvider.hash(rawOtp))
                .thenReturn(otpHash);

        when(tokenProvider.generateAccessToken(user))
                .thenReturn("access-123");

        String rawRefreshToken = "refresh-123";
        when(tokenProvider.generateRefreshToken(user))
                .thenReturn(rawRefreshToken);

        String refreshTokenHash = "hashed-refresh-token";
        when(hashProvider.hash(rawRefreshToken))
                .thenReturn(refreshTokenHash);

        when(tokenProvider.getRefreshTokenTtl())
                .thenReturn(Duration.ofDays(7));

        VerifyEmailUseCase.VerifyEmailResult result =
                useCase.verify(command);

        assertEquals(
                "access-123",
                result.accessToken()
        );

        assertEquals(
                rawRefreshToken,
                result.refreshToken()
        );

        verify(user)
                .activate();

        verify(userRepository)
                .save(user);

        verify(otpStore)
                .delete(email);

        verify(refreshTokenStore)
                .save(
                        eq(refreshTokenHash),
                        eq(userId),
                        eq(Duration.ofDays(7))
                );
    }

    @Test
    void shouldRejectWhenUserNotFound() {

        VerifyEmailCommand command =
                new VerifyEmailCommand(email, rawOtp);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.empty());

        assertThrows(
                UserNotFoundException.class,
                () -> useCase.verify(command)
        );

        verifyNoInteractions(
                otpStore,
                tokenProvider,
                refreshTokenStore,
                hashProvider
        );
    }

    @Test
    void shouldRejectWhenUserAlreadyActive() {

        VerifyEmailCommand command =
                new VerifyEmailCommand(email, rawOtp);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(true);

        assertThrows(
                UserAlreadyActiveException.class,
                () -> useCase.verify(command)
        );

        verifyNoInteractions(
                otpStore,
                tokenProvider,
                refreshTokenStore,
                hashProvider
        );
    }

    @Test
    void shouldRejectWhenVerificationIsBlocked() {

        VerifyEmailCommand command =
                new VerifyEmailCommand(email, rawOtp);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(otpStore.isVerificationBlocked(email))
                .thenReturn(true);

        assertThrows(
                VerificationBlockedException.class,
                () -> useCase.verify(command)
        );

        verify(
                otpStore,
                never()
        ).getOtpHash(any());

        verifyNoInteractions(
                tokenProvider,
                refreshTokenStore,
                hashProvider
        );
    }

    @Test
    void shouldRejectWhenOtpIsMissingOrExpired() {

        VerifyEmailCommand command =
                new VerifyEmailCommand(email, rawOtp);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(otpStore.isVerificationBlocked(email))
                .thenReturn(false);

        when(otpStore.getOtpHash(email))
                .thenReturn(Optional.empty());

        assertThrows(
                InvalidOtpException.class,
                () -> useCase.verify(command)
        );

        verifyNoInteractions(
                hashProvider,
                tokenProvider,
                refreshTokenStore
        );
    }

    @Test
    void shouldIncrementAttemptsAndRejectWhenOtpIsInvalid() {

        VerifyEmailCommand command =
                new VerifyEmailCommand(
                        email,
                        "654321"
                );

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(otpStore.isVerificationBlocked(email))
                .thenReturn(false);

        when(otpStore.getOtpHash(email))
                .thenReturn(Optional.of(otpHash));

        when(hashProvider.hash("654321"))
                .thenReturn("wrong-hash");

        when(
                otpStore.incrementFailedAttempts(
                        eq(email),
                        eq(Duration.ofMinutes(7))
                )
        ).thenReturn(2L);

        assertThrows(
                InvalidOtpException.class,
                () -> useCase.verify(command)
        );

        verify(otpStore)
                .incrementFailedAttempts(
                        eq(email),
                        eq(Duration.ofMinutes(7))
                );

        verify(
                otpStore,
                never()
        ).blockVerification(
                any(),
                any()
        );

        verify(
                user,
                never()
        ).activate();

        verifyNoInteractions(
                tokenProvider,
                refreshTokenStore
        );
    }

    @Test
    void shouldBlockVerificationOnThirdFailedAttempt() {

        VerifyEmailCommand command =
                new VerifyEmailCommand(
                        email,
                        "654321"
                );

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        when(otpStore.isVerificationBlocked(email))
                .thenReturn(false);

        when(otpStore.getOtpHash(email))
                .thenReturn(Optional.of(otpHash));

        when(hashProvider.hash("654321"))
                .thenReturn("wrong-hash");

        when(
                otpStore.incrementFailedAttempts(
                        eq(email),
                        eq(Duration.ofMinutes(7))
                )
        ).thenReturn(3L);

        assertThrows(
                VerificationBlockedException.class,
                () -> useCase.verify(command)
        );

        verify(otpStore)
                .incrementFailedAttempts(
                        eq(email),
                        eq(Duration.ofMinutes(7))
                );

        verify(otpStore)
                .blockVerification(
                        eq(email),
                        eq(Duration.ofMinutes(15))
                );

        verify(
                user,
                never()
        ).activate();

        verifyNoInteractions(
                tokenProvider,
                refreshTokenStore
        );
    }
}