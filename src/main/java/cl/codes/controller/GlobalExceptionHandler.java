package cl.codes.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> noEncontrado(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflicto(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> prohibido(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "No tienes permisos para realizar esta acción"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validacionFallida(MethodArgumentNotValidException e) {
        Map<String, String> errores = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                errores.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errores", errores));
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
    public ResponseEntity<?> errorInesperado(Exception e) {
        String idCorrelacion = UUID.randomUUID().toString().substring(0, 8);
        log.error("Error inesperado [{}]: {}", idCorrelacion, e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "No fue posible completar la operación. Intenta nuevamente.",
                "referencia", idCorrelacion
        ));
    }
}
