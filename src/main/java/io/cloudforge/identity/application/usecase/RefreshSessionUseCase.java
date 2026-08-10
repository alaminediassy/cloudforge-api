package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.RefreshSessionCommand;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.application.port.RefreshTokenStore;
import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.InvalidRefreshTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshSessionUseCase {

    private final TokenProvider tokenProvider;
    private final HashProvider hashProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final UserRepository userRepository;

    public RefreshSessionResult refresh(RefreshSessionCommand command) {
        String rawToken = command.refreshToken();

        if (!tokenProvider.isValidRefreshToken(rawToken)) {
            throw new InvalidRefreshTokenException();
        }

        String tokenHash = hashProvider.hash(rawToken);

        UUID userId = refreshTokenStore.consume(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        User user = userRepository.findById(userId)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!user.isActive()) {
            throw new InvalidRefreshTokenException();
        }

        String newAccessToken = tokenProvider.generateAccessToken(user);

        String newRefreshToken = tokenProvider.generateRefreshToken(user);

        String newTokenHash = hashProvider.hash(newRefreshToken);

        Duration ttl = tokenProvider.getRefreshTokenTtl();

        refreshTokenStore.save(newTokenHash, user.getId(), ttl);

        return new RefreshSessionResult(
                newAccessToken,
                newRefreshToken
        );
    }

    public record RefreshSessionResult(
            String accessToken,
            String refreshToken
    ) {
    }
}