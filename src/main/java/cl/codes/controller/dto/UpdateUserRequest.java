package cl.codes.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotBlank
        @Pattern(regexp = "operator|supervisor|administrator", message = "El rol debe ser operator, supervisor o administrator")
        String rol,

        @NotBlank
        @Pattern(regexp = "bomberos|carabineros|samu", message = "Institución no válida")
        String institution,

        @Size(max = 80)
        String firstName,

        @Size(max = 80)
        String lastName,

        @Email
        @Size(max = 150)
        String email
) {}
