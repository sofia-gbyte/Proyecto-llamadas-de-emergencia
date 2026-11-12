package cl.codes.controller;

import cl.codes.exception.ResourceNotFoundException;
import cl.codes.exception.ExternalServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void externalServiceErrorsReturnActionableBadGatewayResponse() {
        var response = handler.handleExternalServiceError(
                new ExternalServiceException("Revisa GROQ_API_KEY."));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("GROQ_API_KEY"));
    }

    @Test
    void malformedOrNullJsonReturnsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleUnreadableJson(new HttpMessageNotReadableException("Invalid JSON",
                        new MockHttpInputMessage(new byte[0]))).getStatusCode());
    }

    @Test
    void missingMultipartPartReturnsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleMissingRequestPart(new MissingServletRequestPartException("datos")).getStatusCode());
    }

    @Test
    void unexpectedErrorsReturnOnlyRedactedMessageAndReference() {
        var response = handler.handleUnexpectedError(new IllegalStateException("sensitive-marker-123"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().toString().contains("sensitive-marker-123"));
        assertTrue(response.getBody().toString().contains("referencia"));
    }
}
