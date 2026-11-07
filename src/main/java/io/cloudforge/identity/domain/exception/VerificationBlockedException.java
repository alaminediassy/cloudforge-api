package io.cloudforge.identity.domain.exception;

public class VerificationBlockedException extends CloudForgeException {
    public VerificationBlockedException(String email) {
        super("Too many failed attempts. Verification is temporarily blocked for: " + email);
    }
}
