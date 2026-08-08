package io.cloudforge.identity.application.command;

public record LoginUserCommand (
        String email,
        String password
){
}
