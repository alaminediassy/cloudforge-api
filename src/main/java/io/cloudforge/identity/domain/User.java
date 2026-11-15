package io.cloudforge.identity.domain;

import io.cloudforge.identity.domain.exception.InvalidProfileException;
import lombok.Getter;
import io.cloudforge.identity.domain.exception.InvalidUserStateException;
import io.cloudforge.identity.domain.exception.UserAlreadyActiveException;
import java.util.Objects;

import java.util.UUID;

@Getter
public class User {

    private final UUID id;
    private final String email;
    private String firstName;
    private String lastName;
    private String passwordHash;
    private UserStatus status;

    private User(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String passwordHash
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.passwordHash = passwordHash;
        this.status = UserStatus.PENDING;
    }

    public static User create(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String passwordHash
    ) {
        return new User(id, email, firstName, lastName, passwordHash);
    }

    public static User restore(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String passwordHash,
            UserStatus status
    ) {
        User user = new User(
                id,
                email,
                firstName,
                lastName,
                passwordHash
        );

        user.status = Objects.requireNonNull(status, "status");

        return user;
    }

    public void activate() {
        if (isActive()) {
            throw new UserAlreadyActiveException(email);
        }
        if (status != UserStatus.PENDING) {
            throw new InvalidUserStateException();
        }
        this.status = UserStatus.ACTIVE;
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isActive() {
        return UserStatus.ACTIVE.equals(status);
    }

    /**
     * Updates both names after validating both values.
     * If either value is invalid, neither name changes.
     */
    public void updateNames(String firstName, String lastName) {
        validateName(firstName);
        validateName(lastName);

        this.firstName = firstName;
        this.lastName = lastName;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new InvalidProfileException();
        }
    }
}