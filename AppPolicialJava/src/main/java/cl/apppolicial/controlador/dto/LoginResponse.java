package cl.apppolicial.controlador.dto;

public record LoginResponse(String token, String nombreUsuario, String rol, long expiraEnMinutos) {}
