package io.cloudforge.identity.domain.exception;

public class UserAlreadyActiveException extends CloudForgeException {
    public UserAlreadyActiveException(String email) {
        super("User account is already active for email: " + email);
    }
}
