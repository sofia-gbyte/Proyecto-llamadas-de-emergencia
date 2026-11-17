package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MobileLoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        @Size(max = 4096) String captchaToken,
        @Size(max = 6) String totpCode
) {}
