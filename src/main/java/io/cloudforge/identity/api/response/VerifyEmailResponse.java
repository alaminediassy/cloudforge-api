package io.cloudforge.identity.api.response;

public record VerifyEmailResponse(
        String accessToken,
        String refreshToken
) {
}