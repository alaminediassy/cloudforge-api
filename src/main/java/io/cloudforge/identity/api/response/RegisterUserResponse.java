package io.cloudforge.identity.api.response;

import io.cloudforge.identity.domain.UserStatus;

import java.util.UUID;

public record RegisterUserResponse(

        UUID id,
        String email,
        String firstName,
        String lastName,
        UserStatus status

) {
}