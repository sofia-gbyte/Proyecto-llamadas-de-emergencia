package cl.apppolicial.controlador.dto;

import jakarta.validation.constraints.NotBlank;

public record CerrarRequest(@NotBlank String comentario) {}
