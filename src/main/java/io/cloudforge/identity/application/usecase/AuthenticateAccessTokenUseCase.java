package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.port.TokenProvider;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthenticateAccessTokenUseCase {
    private final TokenProvider tokenProvider;
    private final UserRepository userRepository;

    public Optional<UUID> authenticate(String token) {
        return tokenProvider.validateAccessToken(token)
                .flatMap(userRepository::findById)
                .filter(User::isActive)
                .map(User::getId);
    }
}
