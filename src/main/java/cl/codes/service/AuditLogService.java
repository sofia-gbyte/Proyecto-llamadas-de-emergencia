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

    private final Path archivo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    public AuditLogService(@Value("${app.log-auditoria-path:./logs/auditoria.log}") String ruta) {
        this.archivo = Path.of(ruta);
    }

    /**
     * @param actor       usuario que realizó la acción (o el correo/identificador disponible si no hay sesión, o null)
     * @param accion      identificador corto y estable de la acción, p.ej. "login", "assign-call", "disable-user"
     * @param tipoRecurso tipo de recurso afectado, p.ej. "user", "call", "session"
     * @param idRecurso   identificador del recurso afectado (puede ser null)
     * @param ip          IP de origen de la petición
     * @param resultado   "success", "failure", "requested", etc.
     */
    public void registrar(String actor, String accion, String tipoRecurso, String idRecurso, String ip, String resultado) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("timestamp", LocalDateTime.now().format(TS));
        evento.put("actor", actor != null ? actor : "anonymous");
        evento.put("accion", accion);
        evento.put("tipoRecurso", tipoRecurso);
        evento.put("idRecurso", idRecurso);
        evento.put("ip", ip);
        evento.put("resultado", resultado);

        synchronized (lock) {
            try {
                Path carpeta = archivo.toAbsolutePath().getParent();
                if (carpeta != null) Files.createDirectories(carpeta);
                String linea = mapper.writeValueAsString(evento) + System.lineSeparator();
                Files.writeString(archivo, linea, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("No se pudo escribir en el log de auditoría ({}): {}", archivo, e.getMessage());
            }
        }
    }
}
