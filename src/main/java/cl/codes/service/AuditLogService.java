package cl.codes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auditoría persistente de acciones sensibles: login, cambios de usuario,
 * asignación/cierre de llamadas, recuperación de contraseña. Cada evento
 * queda como una línea JSON en {@code app.log-auditoria-path} (ya existía
 * esa propiedad en application.properties, pero no estaba conectada a
 * nada), separado de los logs generales de la aplicación.
 *
 * Pensado para poder responder preguntas como "¿quién cerró este caso?"
 * o "¿quién desactivó a este usuario?" sin depender de logs de consola
 * que rotan o se pierden.
 *
 * Un fallo al escribir el archivo de auditoría NUNCA debe interrumpir la
 * operación real (login, asignar llamada, etc.): se registra el error en
 * el log normal de la aplicación y se continúa.
 */
@Service
public class AuditLogService {
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Path logFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    public AuditLogService(@Value("${app.log-auditoria-path:./logs/auditoria.log}") String filePath) {
        this.logFile = Path.of(filePath);
    }

    /**
     * @param actor       usuario que realizó la acción (o el correo/identificador disponible si no hay sesión, o null)
     * @param accion      identificador corto y estable de la acción, p.ej. "login", "assign-call", "disable-user"
     * @param tipoRecurso tipo de recurso afectado, p.ej. "user", "call", "session"
     * @param idRecurso   identificador del recurso afectado (puede ser null)
     * @param ip          IP de origen de la petición
     * @param outcome     "success", "failure", "requested", etc.
     */
    public void recordEvent(String actor, String action, String resourceType, String resourceId, String ip, String outcome) {
        Map<String, Object> eventRecord = new LinkedHashMap<>();
        eventRecord.put("timestamp", LocalDateTime.now().format(TS));
        eventRecord.put("actor", actor != null ? actor : "anonymous");
        eventRecord.put("accion", action);
        eventRecord.put("tipoRecurso", resourceType);
        eventRecord.put("idRecurso", resourceId);
        eventRecord.put("ip", ip);
        eventRecord.put("resultado", outcome);

        synchronized (lock) {
            try {
                Path parentDirectory = logFile.toAbsolutePath().getParent();
                if (parentDirectory != null) Files.createDirectories(parentDirectory);
                String line = mapper.writeValueAsString(eventRecord) + System.lineSeparator();
                Files.writeString(logFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("No se pudo escribir en el log de auditoría ({}): {}", logFile, e.getMessage());
            }
        }
    }
}
