package io.cloudforge.identity.application.command;

public record RefreshSessionCommand(
        String refreshToken
) {
}