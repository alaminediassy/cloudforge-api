package io.cloudforge.identity.api.response;

public record LoginUserResponse(
        String accessToken,
        String refreshToken
) {
}
