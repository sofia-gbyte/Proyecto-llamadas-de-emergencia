package cl.codes.controller.dto;

public record CreateLiveCallRequest(
        String transcription,
        Double latitudOperador,
        Double longitudOperador
) {}

