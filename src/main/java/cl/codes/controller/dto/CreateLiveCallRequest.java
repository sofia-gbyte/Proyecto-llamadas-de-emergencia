package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLiveCallRequest(
        @NotBlank(message = "La transcripción no puede estar vacía")
        String transcription,
        Double latitudOperador,
        Double longitudOperador
) {}


