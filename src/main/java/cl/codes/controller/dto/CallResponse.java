package cl.codes.controller.dto;

import cl.codes.classifier.Classifier;
import cl.codes.model.Call;
import cl.codes.model.CallInformationUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public record CallResponse(
        Long id, String createdAt, String transcription, String operationalSummary, String priority,
        Map<String, Integer> scores, List<String> highlightedWords, List<String> classificationReasons,
        List<String> suggestedInstitutions, List<Classifier.InstitutionRecommendation> institutionRecommendations,
        List<String> incidentCategories, String address, Double latitude, Double longitude,
        boolean assigned, String assignedOperator, String assignmentDate, String closureDate, String closureComment, String institution,
        List<CallInformationUpdate> informationUpdates
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(CallResponse.class);

    public static CallResponse from(Call call) {
        List<String> words = readList(call.getHighlightedWords(), "highlighted words");
        List<String> reasons = readList(call.getClassificationReasons(), "classification reasons");
        List<String> institutions = readList(call.getSuggestedInstitutions(), "suggested institutions");
        Classifier.ClassificationResult classification =
                Classifier.classifyCall(classificationText(call));
        List<Classifier.InstitutionRecommendation> recommendations =
                readRecommendations(call.getInstitutionRecommendations());
        if (recommendations.isEmpty()) {
            recommendations = classification.institutionRecommendations();
        }

        return new CallResponse(
                call.getId(),
                call.getCreatedAt() != null ? call.getCreatedAt().toString() : null,
                call.getTranscription(),
                call.getOperationalSummary(),
                call.getPriority(),
                Map.of(
                        "urgente", call.getUrgentScore(),
                        "roja", call.getRedScore(),
                        "media", call.getMediumScore(),
                        "verde", call.getGreenScore()
                ),
                words,
                reasons,
                institutions,
                recommendations,
                classification.incidentCategories(),
                call.getDetectedAddress(),
                call.getLatitude(),
                call.getLongitude(),
                call.isAssigned(),
                call.getAssignedOperator(),
                call.getAssignmentDate() != null ? call.getAssignmentDate().toString() : null,
                call.getClosureDate() != null ? call.getClosureDate().toString() : null,
                call.getClosureComment(),
                call.getInstitution(),
                readInformationUpdates(call.getInformationUpdates())
        );
    }

    private static String classificationText(Call call) {
        String transcription = call.getTranscription() == null ? "" : call.getTranscription();
        List<CallInformationUpdate> updates = readInformationUpdates(call.getInformationUpdates());
        if (updates.isEmpty()) return transcription;
        return transcription + "\n" + updates.stream()
                .map(CallInformationUpdate::information)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static List<CallInformationUpdate> readInformationUpdates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Stored call information updates are invalid JSON; omitting them.", e);
            return List.of();
        }
    }

    private static List<String> readList(String json, String fieldName) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Stored {} is invalid JSON; omitting it.", fieldName, e);
            return List.of();
        }
    }

    private static List<Classifier.InstitutionRecommendation> readRecommendations(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Stored institution recommendations are invalid JSON; omitting them.", e);
            return List.of();
        }
    }
}
