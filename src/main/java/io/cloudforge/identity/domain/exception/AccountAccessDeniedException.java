package io.cloudforge.identity.domain.exception;

public class AccountAccessDeniedException extends CloudForgeException {
    public AccountAccessDeniedException() {
        super("Authentication is required or invalid.");
    }
}
