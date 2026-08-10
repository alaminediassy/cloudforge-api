package io.cloudforge.identity.api.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailRequest(

        @NotBlank(message = "L'email est requis")
        @Email(message = "Le format de l'email est invalide")
        String email,

        @NotBlank(message = "Le code OTP est requis")
        @Pattern(
                regexp = "^\\d{6}$",
                message = "Le code OTP doit contenir exactement 6 chiffres"
        )
        String otp
) {
}