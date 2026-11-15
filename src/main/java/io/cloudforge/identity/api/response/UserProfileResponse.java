package io.cloudforge.identity.api.response;

import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String firstName,
        String lastName
) {
}
