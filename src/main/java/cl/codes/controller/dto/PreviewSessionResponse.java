package cl.codes.controller.dto;

/**
 * Respuesta al crear una sesión de "vista previa" (admin viendo la
 * interfaz de operador de una institución). readOnly siempre es true:
 * esta sesión nunca puede asignar, cerrar ni crear llamadas reales.
 */
public record PreviewSessionResponse(
        String token,
        String username,
        String institution,
        String role,
        long expiresInMinutes,
        boolean readOnly
) {}
