package cl.codes.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record CloseRequest(@NotBlank String comment) {}


