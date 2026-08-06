package io.cloudforge.identity.domain.exception;

public abstract class CloudForgeException extends RuntimeException {

    protected CloudForgeException(String message) {
        super(message);
    }

}