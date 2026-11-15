package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.AccountAccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetMyProfileUseCase {

    private final UserRepository userRepository;

    /**
     * Return the profile of the authenticated user.
     * The caller must get the user ID from the authenticated principal,
     * never from a request parameter or body.
     */
    public ProfileResult getProfile(UUID authenticatedUserId) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(AccountAccessDeniedException::new);

// The account may have changed since authentication.
        if (!user.isActive()) {
            throw new AccountAccessDeniedException();
        }

        return new ProfileResult(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName()
        );
    }

    public record ProfileResult(
            UUID id,
            String email,
            String firstName,
            String lastName
    ) {
    }
}
