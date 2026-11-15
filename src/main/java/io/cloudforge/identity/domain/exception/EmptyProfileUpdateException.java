package io.cloudforge.identity.domain.exception;

public class EmptyProfileUpdateException extends CloudForgeException {

    public EmptyProfileUpdateException() {
        super("Provide at least one name to update.");
    }
}