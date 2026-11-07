package io.cloudforge.identity.domain.exception;

public class UserAlreadyExistsException extends CloudForgeException {

    public UserAlreadyExistsException(String email) {
        super("A user with email '" + email + "' already exists.");
    }

}