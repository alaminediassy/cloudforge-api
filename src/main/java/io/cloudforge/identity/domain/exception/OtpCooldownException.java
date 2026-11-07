package io.cloudforge.identity.domain.exception;

public class OtpCooldownException extends CloudForgeException {
    public OtpCooldownException(String email) {
        super("Please wait before requesting a new activation code for: " + email);
    }
}
