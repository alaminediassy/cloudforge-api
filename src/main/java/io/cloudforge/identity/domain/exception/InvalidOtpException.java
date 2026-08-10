package io.cloudforge.identity.domain.exception;

public class InvalidOtpException extends CloudForgeException {

    public InvalidOtpException() {
        super("validation code is invalid or expired for");
    }
}
