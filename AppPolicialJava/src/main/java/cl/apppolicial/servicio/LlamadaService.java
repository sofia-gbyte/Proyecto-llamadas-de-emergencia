package cl.apppolicial.servicio;

import cl.apppolicial.modelo.Llamada;
import cl.apppolicial.repositorio.LlamadaRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class LlamadaService {

    private final LlamadaRepository repo;

    // Orden explícito de prioridad: URGENTE primero. El orden alfabético
    // por defecto NO sirve aquí (ROJA < URGENTE < VERDE alfabéticamente).
    private static final Map<String, Integer> ORDEN_PRIORIDAD = Map.of(
            "URGENTE", 0, "ROJA", 1, "MEDIA", 2, "VERDE", 3
    );

    public LlamadaService(LlamadaRepository repo) {
        this.repo = repo;
    }

    public List<Llamada> obtenerPendientes() {
        List<Llamada> llamadas = repo.findByAsignadoFalse();
        llamadas.sort(
                Comparator.<Llamada, Integer>comparing(l -> ORDEN_PRIORIDAD.getOrDefault(l.getPrioridad(), 4))
                        .thenComparing(Llamada::getFechaHora)
        );
        return llamadas;
    }

    public List<Llamada> obtenerEnProgreso() {
        List<Llamada> llamadas = repo.findByAsignadoTrueAndFechaCierreIsNull();
        llamadas.sort(Comparator.comparing(Llamada::getFechaAsignacion).reversed());
        return llamadas;
    }

    public List<Llamada> obtenerCerradas(int limite) {
        List<Llamada> llamadas = repo.findByFechaCierreIsNotNullOrderByFechaCierreDesc();
        return llamadas.size() > limite ? llamadas.subList(0, limite) : llamadas;
    }

    public Llamada asignar(Long id, String operador) {
        Llamada l = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Llamada no encontrada"));
        if (l.isAsignado()) {
            throw new IllegalStateException("Esta llamada ya fue tomada por otro operador");
        }
        l.setAsignado(true);
        l.setOperadorAsignado(operador.strip());
        l.setFechaAsignacion(LocalDateTime.now());
        return repo.save(l);
    }

    public Llamada cerrar(Long id, String comentario) {
        Llamada l = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Llamada no encontrada"));
        if (!l.isAsignado()) {
            throw new IllegalStateException("No se puede cerrar un caso que no ha sido asignado");
        }
        l.setFechaCierre(LocalDateTime.now());
        l.setComentarioCierre(comentario.strip());
        return repo.save(l);
    }

    public record Metricas(
            long totalLlamadas,
            long urgentesActivas,
            long pendientes,
            long enProgreso,
            Double tiempoPromedioRespuestaSeg
    ) {}

    public Metricas metricas() {
        long total = repo.count();
        long urgentesActivas = repo.countByPrioridadAndFechaCierreIsNull("URGENTE");
        long pendientes = repo.countByAsignadoFalse();
        long enProgreso = repo.countByAsignadoTrueAndFechaCierreIsNull();

        List<Llamada> conAsignacion = repo.findByFechaAsignacionIsNotNull();
        var promedioOpt = conAsignacion.stream()
                .filter(l -> l.getFechaHora() != null && l.getFechaAsignacion() != null)
                .mapToLong(l -> Duration.between(l.getFechaHora(), l.getFechaAsignacion()).getSeconds())
                .average();
        Double tiempoPromedio = promedioOpt.isPresent()
                ? Math.round(promedioOpt.getAsDouble() * 10.0) / 10.0
                : null;

        return new Metricas(total, urgentesActivas, pendientes, enProgreso, tiempoPromedio);
    }
}
