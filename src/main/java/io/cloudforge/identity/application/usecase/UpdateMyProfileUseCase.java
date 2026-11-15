package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.UpdateMyProfileCommand;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.AccountAccessDeniedException;
import io.cloudforge.identity.domain.exception.EmptyProfileUpdateException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UpdateMyProfileUseCase {

    private final UserRepository userRepository;

    /**
     * Updates the authenticated user's names.
     *
     * <p>The caller must get the user ID from the authenticated principal.
     * Missing fields keep the values read while holding the account lock.
     */
    public UpdateProfileResult updateProfile(
            UUID authenticatedUserId,
            UpdateMyProfileCommand command
    ) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId");
        Objects.requireNonNull(command, "command");

        if (command.firstName() == null && command.lastName() == null) {
            throw new EmptyProfileUpdateException();
        }

        User user = userRepository.findByIdForUpdate(authenticatedUserId)
                .orElseThrow(AccountAccessDeniedException::new);

        if (!user.isActive()) {
            throw new AccountAccessDeniedException();
        }

        String firstName = command.firstName() != null
                ? command.firstName()
                : user.getFirstName();

        String lastName = command.lastName() != null
                ? command.lastName()
                : user.getLastName();

        user.updateNames(firstName, lastName);

        int updated = userRepository.updateNames(
                user.getId(),
                user.getFirstName(),
                user.getLastName()
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Expected one locked account to be updated."
            );
        }

        return new UpdateProfileResult(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName()
        );
    }

    public record UpdateProfileResult(
            UUID id,
            String email,
            String firstName,
            String lastName
    ) {
    }
}