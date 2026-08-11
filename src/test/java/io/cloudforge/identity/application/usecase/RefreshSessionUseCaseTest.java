package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.RefreshSessionCommand;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.application.port.RefreshTokenStore;
import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.InvalidRefreshTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshSessionUseCaseTest {

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private HashProvider hashProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private UserRepository userRepository;

    @Mock
    private User user;

    private RefreshSessionUseCase refreshSessionUseCase;

    private final UUID userId = UUID.randomUUID();

    private final String rawToken = "raw-refresh-token";

    private final String tokenHash = "hashed-refresh-token";

    private final RefreshSessionCommand command =
            new RefreshSessionCommand(rawToken);

    @BeforeEach
    void setUp() {
        refreshSessionUseCase = new RefreshSessionUseCase(
                tokenProvider,
                hashProvider,
                refreshTokenStore,
                userRepository
        );
    }

    @Test
    void shouldRefreshSuccessfully() {

        String newAccessToken = "new-access-token";
        String newRefreshToken = "new-refresh-token";
        String newTokenHash = "hashed-new-refresh-token";

        Duration ttl = Duration.ofDays(7);

        when(tokenProvider.isValidRefreshToken(rawToken))
                .thenReturn(true);

        when(hashProvider.hash(rawToken))
                .thenReturn(tokenHash);

        when(refreshTokenStore.consume(tokenHash))
                .thenReturn(Optional.of(userId));

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(true);

        when(user.getId())
                .thenReturn(userId);

        when(tokenProvider.generateAccessToken(user))
                .thenReturn(newAccessToken);

        when(tokenProvider.generateRefreshToken(user))
                .thenReturn(newRefreshToken);

        when(hashProvider.hash(newRefreshToken))
                .thenReturn(newTokenHash);

        when(tokenProvider.getRefreshTokenTtl())
                .thenReturn(ttl);

        RefreshSessionUseCase.RefreshSessionResult result =
                refreshSessionUseCase.refresh(command);

        assertNotNull(result);

        assertEquals(
                newAccessToken,
                result.accessToken()
        );

        assertEquals(
                newRefreshToken,
                result.refreshToken()
        );

        verify(tokenProvider)
                .isValidRefreshToken(rawToken);

        verify(hashProvider)
                .hash(rawToken);

        verify(hashProvider)
                .hash(newRefreshToken);

        InOrder inOrder = inOrder(refreshTokenStore);

        inOrder.verify(refreshTokenStore)
                .consume(tokenHash);

        inOrder.verify(refreshTokenStore)
                .save(
                        newTokenHash,
                        userId,
                        ttl
                );

        verify(refreshTokenStore, never())
                .exists(anyString());

        verify(refreshTokenStore, never())
                .revoke(anyString());
    }

    @Test
    void shouldRejectInvalidJwt() {

        when(tokenProvider.isValidRefreshToken(rawToken))
                .thenReturn(false);

        assertThrows(
                InvalidRefreshTokenException.class,
                () -> refreshSessionUseCase.refresh(command)
        );

        verifyNoInteractions(
                hashProvider,
                refreshTokenStore,
                userRepository
        );

        verify(tokenProvider, never())
                .generateAccessToken(any());

        verify(tokenProvider, never())
                .generateRefreshToken(any());
    }

    @Test
    void shouldRejectWhenTokenAbsentFromRedis() {

        when(tokenProvider.isValidRefreshToken(rawToken))
                .thenReturn(true);

        when(hashProvider.hash(rawToken))
                .thenReturn(tokenHash);

        when(refreshTokenStore.consume(tokenHash))
                .thenReturn(Optional.empty());

        assertThrows(
                InvalidRefreshTokenException.class,
                () -> refreshSessionUseCase.refresh(command)
        );

        verify(refreshTokenStore)
                .consume(tokenHash);

        verifyNoInteractions(userRepository);

        verify(tokenProvider, never())
                .generateAccessToken(any());

        verify(tokenProvider, never())
                .generateRefreshToken(any());

        verify(refreshTokenStore, never())
                .save(anyString(), any(UUID.class), any(Duration.class));
    }

    @Test
    void shouldRejectWhenUserNotFound() {

        when(tokenProvider.isValidRefreshToken(rawToken))
                .thenReturn(true);

        when(hashProvider.hash(rawToken))
                .thenReturn(tokenHash);

        when(refreshTokenStore.consume(tokenHash))
                .thenReturn(Optional.of(userId));

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        assertThrows(
                InvalidRefreshTokenException.class,
                () -> refreshSessionUseCase.refresh(command)
        );

        verify(refreshTokenStore)
                .consume(tokenHash);

        verify(tokenProvider, never())
                .generateAccessToken(any());

        verify(tokenProvider, never())
                .generateRefreshToken(any());

        verify(refreshTokenStore, never())
                .save(anyString(), any(UUID.class), any(Duration.class));
    }

    @Test
    void shouldRejectWhenUserNotActive() {

        when(tokenProvider.isValidRefreshToken(rawToken))
                .thenReturn(true);

        when(hashProvider.hash(rawToken))
                .thenReturn(tokenHash);

        when(refreshTokenStore.consume(tokenHash))
                .thenReturn(Optional.of(userId));

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        when(user.isActive())
                .thenReturn(false);

        assertThrows(
                InvalidRefreshTokenException.class,
                () -> refreshSessionUseCase.refresh(command)
        );

        verify(tokenProvider, never())
                .generateAccessToken(any());

        verify(tokenProvider, never())
                .generateRefreshToken(any());

        verify(refreshTokenStore, never())
                .save(anyString(), any(UUID.class), any(Duration.class));
    }
}