package cl.apppolicial.controlador.dto;

import cl.apppolicial.modelo.Llamada;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public record LlamadaResponse(
        Long id,
        String fechaHora,
        String transcripcion,
        String resumenOperativo,
        String prioridad,
        Map<String, Integer> puntajes,
        List<String> palabrasDestacadas,
        String direccion,
        Double latitud,
        Double longitud,
        boolean asignado,
        String operadorAsignado,
        String fechaAsignacion,
        String fechaCierre,
        String comentarioCierre
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static LlamadaResponse de(Llamada l) {
        List<String> palabras;
        try {
            palabras = l.getPalabrasDestacadas() == null
                    ? List.of()
                    : MAPPER.readValue(l.getPalabrasDestacadas(), List.class);
        } catch (Exception e) {
            palabras = List.of();
        }

        return new LlamadaResponse(
                l.getId(),
                l.getFechaHora() != null ? l.getFechaHora().toString() : null,
                l.getTranscripcion(),
                l.getResumenOperativo(),
                l.getPrioridad(),
                Map.of(
                        "urgente", l.getPuntajeUrgente(),
                        "roja", l.getPuntajeRoja(),
                        "media", l.getPuntajeMedia(),
                        "verde", l.getPuntajeVerde()
                ),
                palabras,
                l.getDireccionDetectada(),
                l.getLatitud(),
                l.getLongitud(),
                l.isAsignado(),
                l.getOperadorAsignado(),
                l.getFechaAsignacion() != null ? l.getFechaAsignacion().toString() : null,
                l.getFechaCierre() != null ? l.getFechaCierre().toString() : null,
                l.getComentarioCierre()
        );
    }
}
