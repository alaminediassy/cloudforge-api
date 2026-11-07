package io.cloudforge.identity.domain.exception;

public class InvalidUserStateException extends CloudForgeException {
    public InvalidUserStateException() {
        super("Account cannot be activated.");
    }
}
