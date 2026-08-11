package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.LogoutCommand;
import io.cloudforge.identity.application.port.HashProvider;
import io.cloudforge.identity.application.port.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutUseCase {

    private final HashProvider hashProvider;
    private final RefreshTokenStore refreshTokenStore;

    public void logout(LogoutCommand command) {
        String tokenHash = hashProvider.hash(command.refreshToken());

        refreshTokenStore.revoke(tokenHash);
    }
}
