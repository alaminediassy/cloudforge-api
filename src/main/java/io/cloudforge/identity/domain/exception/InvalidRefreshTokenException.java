package io.cloudforge.identity.domain.exception;

public class InvalidRefreshTokenException extends CloudForgeException {

    public InvalidRefreshTokenException() {
        super("The refresh token is invalid or expired.");
    }
}