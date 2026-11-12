package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CallInformationUpdateRequest(
        @NotBlank
        @Pattern(regexp = "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        String updateId,
        @NotBlank
        @Size(max = 5000)
        String information
) {}
