package io.cloudforge.identity.api.response;

public record RefreshSessionResponse(
        String accessToken,
        String refreshToken
) {
}