package cl.codes.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min=3,max=50,message="El user debe tener entre 3 y 50 caracteres")
        @Pattern(regexp="^[a-zA-Z0-9._-]+$", message="El user solo puede tener letras, números, puntos, guiones y guiones bajos")
        String username,
        @NotBlank @Size(min=10,message="La contraseña debe tener al menos 10 caracteres") String password,
        @NotBlank @Size(max=80) String nombre,
        @NotBlank @Size(max=80) String apellido,
        @NotBlank @Email @Size(max=150) String correo,
        @NotBlank @Pattern(regexp="bomberos|carabineros|samu", message="Institución no válida") String institucion
) {}


