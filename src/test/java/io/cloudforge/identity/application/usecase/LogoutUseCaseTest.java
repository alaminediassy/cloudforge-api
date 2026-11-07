package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.LogoutCommand;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.application.port.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoutUseCaseTest {

    @Mock
    private HashProvider hashProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private LogoutUseCase logoutUseCase;

    @BeforeEach
    void setUp() {
        logoutUseCase = new LogoutUseCase(
                hashProvider,
                refreshTokenStore
        );
    }

    @Test
    void shouldRevokeTokenSuccessfully() {
        String rawToken = "valid-refresh-token";
        String tokenHash = "hashed-refresh-token";

        LogoutCommand command = new LogoutCommand(rawToken);

        when(hashProvider.hash(rawToken))
                .thenReturn(tokenHash);

        logoutUseCase.logout(command);

        verify(hashProvider).hash(rawToken);
        verify(refreshTokenStore).revoke(tokenHash);

        verifyNoMoreInteractions(
                hashProvider,
                refreshTokenStore
        );
    }

    @Test
    void shouldRevokeTokenEvenIfItMayNotExist() {
        String rawToken = "unknown-or-expired-refresh-token";
        String tokenHash = "hashed-unknown-token";

        LogoutCommand command = new LogoutCommand(rawToken);

        when(hashProvider.hash(rawToken))
                .thenReturn(tokenHash);

        logoutUseCase.logout(command);

        verify(hashProvider).hash(rawToken);
        verify(refreshTokenStore).revoke(tokenHash);

        verifyNoMoreInteractions(
                hashProvider,
                refreshTokenStore
        );
    }

    @Test
    void shouldOnlyHashAndRevokeToken() {
        String rawToken = "refresh-token";
        String tokenHash = "hashed-token";

        LogoutCommand command = new LogoutCommand(rawToken);

        when(hashProvider.hash(rawToken))
                .thenReturn(tokenHash);

        logoutUseCase.logout(command);

        verify(hashProvider).hash(rawToken);
        verify(refreshTokenStore).revoke(tokenHash);

        verifyNoMoreInteractions(
                hashProvider,
                refreshTokenStore
        );
    }
}