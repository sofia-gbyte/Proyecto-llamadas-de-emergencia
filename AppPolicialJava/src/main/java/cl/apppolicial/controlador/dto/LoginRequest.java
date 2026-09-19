package cl.apppolicial.controlador.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String nombreUsuario, @NotBlank String password) {}
