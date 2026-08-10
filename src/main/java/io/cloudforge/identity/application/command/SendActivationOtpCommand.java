package io.cloudforge.identity.application.command;

public record SendActivationOtpCommand(
        String email
) {
}
