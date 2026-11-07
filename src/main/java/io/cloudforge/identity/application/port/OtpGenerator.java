package io.cloudforge.identity.application.port;

public interface OtpGenerator {
    /** Returns an unpredictable six-digit code, including any leading zeros. */
    String generate();
}
