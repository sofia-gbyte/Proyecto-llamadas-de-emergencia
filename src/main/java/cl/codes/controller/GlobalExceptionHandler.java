package cl.codes.controller;

import cl.codes.exception.ExternalServiceException;
import cl.codes.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manejo centralizado de errores para toda la API. Deliberadamente NO
 * se exponen mensajes de excepción interna genéricos (getMessage() de
 * excepciones no controladas, stack traces, etc.) para evitar filtrar
 * detalles de implementación; solo se devuelven los mensajes que los
 * propios servicios definieron a propósito para el user final.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleInvalidRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<?> handleExternalServiceError(ExternalServiceException e) {
        log.warn("External service request failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleIntegrityConflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "El recurso ya existe o no puede modificarse por una restricción de integridad."
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleFileTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "La solicitud supera el tamaño máximo permitido de 50 MB."
        ));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleMultipartError(MultipartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "No se pudo recibir correctamente la grabación de audio. Vuelve a intentarlo."
        ));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<?> handleMissingRequestPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Falta una parte requerida de la solicitud."
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleForbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "No tienes permisos para realizar esta acción"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationError(MethodArgumentNotValidException e) {
        Map<String, String> validationErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                validationErrors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errores", validationErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "El cuerpo de la solicitud está vacío o contiene JSON inválido."
        ));
    }

    /**
     * Red de seguridad final: cualquier excepción no controlada (NullPointerException,
     * fallos de un servicio externo, lo que sea) NUNCA debe llegar al frontend como
     * stacktrace o mensaje interno. Se registra completo en el log del servidor con un
     * ID de correlación corto, y a la persona que operó solo se le muestra un mensaje
     * genérico junto con ese ID, para que administración pueda buscarlo en los logs
     * si hace falta investigar.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpectedError(Exception e) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        Throwable rootCause = e;
        int causeDepth = 0;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause && causeDepth++ < 32) {
            rootCause = rootCause.getCause();
        }
        // Do not log exception messages or stack traces here: lower-level
        // exceptions can contain paths, request values, or provider details.
        log.error("Error inesperado [{}]: exceptionType={}, causeType={}",
                correlationId, e.getClass().getName(), rootCause.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "No fue posible completar la operación. Intenta nuevamente.",
                "referencia", correlationId
        ));
    }
}
