package io.cloudforge.identity.application.command;

public record LogoutCommand(String refreshToken) {
}