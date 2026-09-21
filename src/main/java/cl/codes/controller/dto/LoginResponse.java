package cl.codes.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginResponse(
        String token, String username,
        String role,
        long expiresInMinutes
) {}


