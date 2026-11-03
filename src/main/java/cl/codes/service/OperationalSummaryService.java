package cl.codes.service;

import cl.codes.classifier.Classifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OperationalSummaryService {
    public String generate(String text, Classifier.ClassificationResult classification) {
        return generate(text, classification, classification.address());
    }

    public String generate(String text, Classifier.ClassificationResult classification, String address) {
        String type = emergencyType(classification.highlightedWords());
        String location = address;
        String normalizedText = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        String requestSummary = extractRequest(normalizedText);
        StringBuilder summary = new StringBuilder();
        if (!type.isBlank()) summary.append(type); else summary.append("Emergencia reportada");
        if (location != null && !location.isBlank()) summary.append(" en ").append(location);
        if (!requestSummary.isBlank()) summary.append(". ").append(requestSummary);
        else if (!normalizedText.isBlank()) summary.append(". Se recibió una llamada de emergencia y se conserva la transcripción completa como respaldo.");
        return summary.toString();
    }

    private String emergencyType(List<String> words) {
        if (words == null) return "";
        for (String word : words) {
            String normalizedWord = word.toLowerCase();
            if (normalizedWord.contains("incend") || normalizedWord.contains("fuego")) return "Incendio reportado";
            if (normalizedWord.contains("dispar") || normalizedWord.contains("arma") || normalizedWord.contains("balazo")) return "Situación con arma reportada";
            if (normalizedWord.contains("accidente") || normalizedWord.contains("choque") || normalizedWord.contains("atropell")) return "Accidente reportado";
            if (normalizedWord.contains("robo") || normalizedWord.contains("asalto") || normalizedWord.contains("ladron")) return "Robo o asalto reportado";
            if (normalizedWord.contains("agres") || normalizedWord.contains("pelea")) return "Agresión reportada";
        }
        return "";
    }

    private String extractRequest(String text) {
        if (text.isBlank()) return "";
        String normalizedText = text.toLowerCase();
        if (normalizedText.contains("bombero"))
            return "Se solicita apoyo de Bomberos.";
        if (normalizedText.contains("patrulla") || normalizedText.contains("policia") || normalizedText.contains("carabinero"))
            return "Se solicita apoyo policial.";
        if (normalizedText.contains("urgencia") || normalizedText.contains("urgente") || normalizedText.contains("con mucha urgencia"))
            return "Se solicita atención con carácter urgente.";
        if (normalizedText.contains("ayuda") || normalizedText.contains("ayuden") || normalizedText.contains("necesitamos"))
            return "La persona solicita asistencia.";
        return "Se recibió una llamada de emergencia; revisar la transcripción para el detalle.";
    }

}
