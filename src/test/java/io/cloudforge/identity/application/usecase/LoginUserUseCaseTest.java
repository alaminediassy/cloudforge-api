package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.LoginUserCommand;
import io.cloudforge.identity.application.port.PasswordHasher;
import io.cloudforge.identity.application.port.RefreshTokenStore;
import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.domain.exception.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginUserUseCaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private HashProvider hashProvider;

    @Mock
    private User user;

    private LoginUserUseCase loginUserUseCase;

    @BeforeEach
    void setUp() {
        loginUserUseCase = new LoginUserUseCase(
                userRepository,
                passwordHasher,
                tokenProvider,
                refreshTokenStore,
                hashProvider
        );
    }

    @Test
    void shouldLoginSuccessfully() {

        LoginUserCommand command = new LoginUserCommand(
                "lamine@cloudforge.io",
                "Passer123"
        );

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.of(user));

        when(user.getPasswordHash())
                .thenReturn("hashed-password");

        when(passwordHasher.matches(
                command.password(),
                "hashed-password"
        )).thenReturn(true);

        when(user.isActive())
                .thenReturn(true);

        when(tokenProvider.generateAccessToken(user))
                .thenReturn("access-token");

        when(tokenProvider.generateRefreshToken(user))
                .thenReturn("refresh-token");

        when(hashProvider.hash("refresh-token")).thenReturn("hash-returned-by-port");

        LoginUserUseCase.LoginResult result =
                loginUserUseCase.login(command);

        verify(hashProvider).hash("refresh-token");
        verify(refreshTokenStore).save(eq("hash-returned-by-port"), any(), any());
        assertNotNull(result);
        assertEquals("access-token", result.accessToken());
        assertEquals("refresh-token", result.refreshToken());

        verify(userRepository).findByEmail(command.email());
        verify(passwordHasher).matches(
                command.password(),
                "hashed-password"
        );
        verify(tokenProvider).generateAccessToken(user);
        verify(tokenProvider).generateRefreshToken(user);
    }

    @Test
    void shouldRejectUnknownEmail() {

        LoginUserCommand command = new LoginUserCommand(
                "unknown@cloudforge.io",
                "Passer123"
        );

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.empty());

        assertThrows(
                InvalidCredentialsException.class,
                () -> loginUserUseCase.login(command)
        );

        verify(userRepository).findByEmail(command.email());
        verifyNoInteractions(passwordHasher, tokenProvider);
    }

    @Test
    void shouldRejectInvalidPassword() {

        LoginUserCommand command = new LoginUserCommand(
                "lamine@cloudforge.io",
                "WrongPassword"
        );

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.of(user));

        when(user.getPasswordHash())
                .thenReturn("hashed-password");

        when(passwordHasher.matches(
                command.password(),
                "hashed-password"
        )).thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> loginUserUseCase.login(command)
        );

        verify(userRepository).findByEmail(command.email());
        verify(passwordHasher).matches(
                command.password(),
                "hashed-password"
        );
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void shouldRejectInactiveUser() {

        LoginUserCommand command = new LoginUserCommand(
                "lamine@cloudforge.io",
                "Passer123"
        );

        when(userRepository.findByEmail(command.email()))
                .thenReturn(Optional.of(user));

        when(user.getPasswordHash())
                .thenReturn("hashed-password");

        when(passwordHasher.matches(
                command.password(),
                "hashed-password"
        )).thenReturn(true);

        when(user.isActive())
                .thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> loginUserUseCase.login(command)
        );

        verify(passwordHasher).matches(
                command.password(),
                "hashed-password"
        );
        verify(user).isActive();
        verifyNoInteractions(tokenProvider);
    }
}