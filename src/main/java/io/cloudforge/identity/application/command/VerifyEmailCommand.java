package io.cloudforge.identity.application.command;

public record VerifyEmailCommand(
        String email,
        String otp
) {
}
