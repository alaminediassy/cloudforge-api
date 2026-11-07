package io.cloudforge.identity.api.security;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record AuthenticatedUser(UUID userId) implements Principal {
    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
