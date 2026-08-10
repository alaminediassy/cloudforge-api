package io.cloudforge.identity.domain.exception;

public class UserNotFoundException extends CloudForgeException {
    public UserNotFoundException(String email) {
        super("User not found for email: " + email);
    }
}
