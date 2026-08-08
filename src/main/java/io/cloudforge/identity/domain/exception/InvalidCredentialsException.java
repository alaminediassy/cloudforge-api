package io.cloudforge.identity.domain.exception;

public class InvalidCredentialsException extends CloudForgeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}