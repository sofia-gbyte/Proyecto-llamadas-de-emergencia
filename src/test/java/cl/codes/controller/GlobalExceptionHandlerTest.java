package cl.codes.controller;

import cl.codes.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void invalidArgumentsReturnBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, handler.solicitudInvalida(new IllegalArgumentException("Invalid input")).getStatusCode());
    }

    @Test
    void missingResourcesReturnNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, handler.noEncontrado(new ResourceNotFoundException("Not found")).getStatusCode());
    }

    @Test
    void conflictingStatesReturnConflict() {
        assertEquals(HttpStatus.CONFLICT, handler.conflicto(new IllegalStateException("Conflict")).getStatusCode());
    }
}
