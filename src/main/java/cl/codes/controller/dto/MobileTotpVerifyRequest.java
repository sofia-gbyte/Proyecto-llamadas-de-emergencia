package cl.codes.controller.dto;

import jakarta.validation.constraints.Pattern;

public record MobileTotpVerifyRequest(
        @Pattern(regexp = "[0-9]{6}") String code
) {}
