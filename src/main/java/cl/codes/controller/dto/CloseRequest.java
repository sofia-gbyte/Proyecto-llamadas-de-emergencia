package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record CerrarRequest(@NotBlank String comentario) {}


