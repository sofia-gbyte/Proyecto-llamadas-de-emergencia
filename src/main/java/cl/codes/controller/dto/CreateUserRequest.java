package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank
        @Size(min = 3, max = 50, message = "El nombre de user debe tener entre 3 y 50 caracteres")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "El nombre de user solo puede tener letras, números, puntos, guiones y guiones bajos")
        String username,

        @NotBlank
        @Size(min = 10, message = "La contraseña debe tener al menos 10 caracteres")
        String password,

        @Pattern(regexp = "operator|supervisor|administrator", message = "El rol debe ser operator, supervisor o administrator")
        String rol
) {}


