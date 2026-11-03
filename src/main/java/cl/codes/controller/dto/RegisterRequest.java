package cl.codes.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min=3,max=50,message="El user debe tener entre 3 y 50 caracteres")
        @Pattern(regexp="^[a-zA-Z0-9._-]+$", message="El user solo puede tener letras, números, puntos, guiones y guiones bajos")
        String username,
        @NotBlank @Size(min=12,message="La contraseña debe tener al menos 12 caracteres")
        @Pattern(regexp="^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z\\d]).+$",
                message="La contraseña debe incluir mayúsculas, minúsculas, números y símbolos") String password,
        @JsonProperty("nombre") @NotBlank @Size(max=80) String firstName,
        @JsonProperty("apellido") @NotBlank @Size(max=80) String lastName,
        @JsonProperty("correo") @NotBlank @Email @Size(max=150) String email,
        @JsonProperty("institucion") @NotBlank @Pattern(regexp="bomberos|carabineros|samu", message="Institución no válida") String institution,
        @Size(max=4096) String captchaToken
) {}
