package io.cloudforge.identity.api.request;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "Le refresh token est requis")
        String refreshToken
) {
}