package cl.apppolicial.controlador.dto;

import jakarta.validation.constraints.NotBlank;

public record CrearLlamadaEnVivoRequest(
        @NotBlank(message = "La transcripción no puede estar vacía")
        String transcripcion,
        Double latitudOperador,
        Double longitudOperador
) {}
