package cl.codes.controller;

import cl.codes.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void invalidArgumentsReturnBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, handler.handleInvalidRequest(new IllegalArgumentException("Invalid input")).getStatusCode());
    }

    @Test
    void missingResourcesReturnNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, handler.handleNotFound(new ResourceNotFoundException("Not found")).getStatusCode());
    }

    @Test
    void conflictingStatesReturnConflict() {
        assertEquals(HttpStatus.CONFLICT, handler.handleConflict(new IllegalStateException("Conflict")).getStatusCode());
    }

    @Test
    void malformedOrNullJsonReturnsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleUnreadableJson(new HttpMessageNotReadableException("Invalid JSON")).getStatusCode());
    }

    @Test
    void missingMultipartPartReturnsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleMissingRequestPart(new MissingServletRequestPartException("datos")).getStatusCode());
    }
}
