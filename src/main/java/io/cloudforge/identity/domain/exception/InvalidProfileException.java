package io.cloudforge.identity.domain.exception;

public class InvalidProfileException extends CloudForgeException {

    public InvalidProfileException() {
        super("First name and last name must contain between 1 and 100 characters.");
    }
}