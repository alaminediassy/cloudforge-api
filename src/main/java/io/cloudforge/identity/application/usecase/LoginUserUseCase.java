package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.LoginUserCommand;
import io.cloudforge.identity.application.port.PasswordHasher;
import io.cloudforge.identity.application.port.RefreshTokenStore;
import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.InvalidCredentialsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@Transactional
@RequiredArgsConstructor
public class LoginUserUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final HashProvider hashProvider;

    public LoginResult login(LoginUserCommand command) {

        User user = userRepository.findByEmail(command.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordHasher.matches(
                command.password(),
                user.getPasswordHash()
        )) {
            throw new InvalidCredentialsException();
        }

        if (!user.isActive()) {
            throw new InvalidCredentialsException();
        }

        String accessToken = tokenProvider.generateAccessToken(user);
        String refreshToken = tokenProvider.generateRefreshToken(user);

        Duration ttl = tokenProvider.getRefreshTokenTtl();

        String tokenHash = hashProvider.hash(refreshToken);

        refreshTokenStore.save(
                tokenHash,
                user.getId(),
                ttl
        );

        return new LoginResult(
                accessToken,
                refreshToken
        );
    }

    public record LoginResult(
            String accessToken,
            String refreshToken
    ) {
    }
}