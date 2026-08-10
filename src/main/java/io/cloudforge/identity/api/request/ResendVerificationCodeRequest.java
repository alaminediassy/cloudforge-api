package io.cloudforge.identity.api.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendVerificationCodeRequest(

        @NotBlank(message = "L'email est requis")
        @Email(message = "Le format de l'email est invalide")
        String email

) {
}