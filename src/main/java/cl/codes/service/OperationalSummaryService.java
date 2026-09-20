package cl.codes.service;

import cl.codes.classifier.Classifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OperationalSummaryService {
    public String generate(String texto, Classifier.ResultadoClasificacion c) {
        String tipo = tipoEmergencia(c.highlightedWords());
        String lugar = c.direccion();
        String limpio = texto == null ? "" : texto.replaceAll("\\s+", " ").trim();
        String fragmento = extraerSolicitud(limpio);
        StringBuilder s = new StringBuilder();
        if (!tipo.isBlank()) s.append(tipo); else s.append("Emergencia reportada");
        if (lugar != null && !lugar.isBlank()) s.append(" en ").append(lugar);
        if (!fragmento.isBlank()) s.append(". ").append(fragmento.replaceAll("\\.$", ""));
        else if (!limpio.isBlank()) s.append(". Se recibió una llamada de emergencia y se conserva la transcripción completa como respaldo.");
        return s.toString();
    }

    private String tipoEmergencia(List<String> palabras) {
        if (palabras == null) return "";
        for (String p : palabras) {
            String n = p.toLowerCase();
            if (n.contains("incend") || n.contains("fuego")) return "Incendio reportado";
            if (n.contains("dispar") || n.contains("arma") || n.contains("balazo")) return "Situación con arma reportada";
            if (n.contains("accidente") || n.contains("choque") || n.contains("atropell")) return "Accidente reportado";
            if (n.contains("robo") || n.contains("asalto") || n.contains("ladron")) return "Robo o asalto reportado";
            if (n.contains("agres") || n.contains("pelea")) return "Agresión reportada";
        }
        return "";
    }

    private String extraerSolicitud(String t) {
        if (t.isBlank()) return "";
        String n = t.toLowerCase();
        if (n.contains("patrulla") || n.contains("policia") || n.contains("carabinero"))
            return "Se solicita apoyo policial.";
        if (n.contains("urgencia") || n.contains("urgente") || n.contains("con mucha urgencia"))
            return "Se solicita atención con carácter urgente.";
        if (n.contains("ayuda") || n.contains("ayuden") || n.contains("necesitamos"))
            return "La persona solicita asistencia.";
        return "Se recibió una llamada de emergencia; revisar la transcripción para el detalle.";
    }

}


