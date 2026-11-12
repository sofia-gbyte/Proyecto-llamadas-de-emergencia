package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCallRequest(
        @NotBlank
        @Size(max = 5000)
        String operationalSummary,

        @Size(max = 200)
        String address
) {}
