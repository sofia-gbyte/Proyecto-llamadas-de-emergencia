package cl.apppolicial.controlador.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CrearUsuarioRequest(
        @NotBlank
        @Size(min = 3, max = 50, message = "El nombre de usuario debe tener entre 3 y 50 caracteres")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "El nombre de usuario solo puede tener letras, números, puntos, guiones y guiones bajos")
        String nombreUsuario,

        @NotBlank
        @Size(min = 10, message = "La contraseña debe tener al menos 10 caracteres")
        String password,

        @Pattern(regexp = "operador|supervisor|administrador", message = "El rol debe ser operador, supervisor o administrador")
        String rol
) {}
