package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank String username,
	@NotBlank String password,
	@Size(max = 4096) String captchaToken
) {}

