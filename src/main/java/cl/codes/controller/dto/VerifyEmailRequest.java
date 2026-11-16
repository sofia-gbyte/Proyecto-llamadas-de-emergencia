package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailRequest(
        @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "El enlace de confirmación no es válido.")
        String token
) {}
