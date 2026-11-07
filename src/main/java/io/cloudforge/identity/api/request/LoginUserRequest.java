package io.cloudforge.identity.api.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import io.cloudforge.identity.api.validation.Utf8Length;

public record LoginUserRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Utf8Length(max = 72)
        String password

) {
}