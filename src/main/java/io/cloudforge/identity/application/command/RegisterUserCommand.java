package io.cloudforge.identity.application.command;

public record RegisterUserCommand(
        String email,
        String firstName,
        String lastName,
        String password
) {
}
