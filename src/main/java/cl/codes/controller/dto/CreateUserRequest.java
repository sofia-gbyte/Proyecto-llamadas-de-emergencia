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
        @Size(min = 12, message = "La contraseña debe tener al menos 12 caracteres")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z\\d]).+$",
                message = "La contraseña debe incluir mayúsculas, minúsculas, números y símbolos")
        String password,

        @NotBlank
        @Pattern(regexp = "operator|supervisor|administrator", message = "El rol debe ser operator, supervisor o administrator")
        String rol,

        @NotBlank
        @Pattern(regexp = "bomberos|carabineros|samu", message = "Institución no válida")
        String institution
) {}


