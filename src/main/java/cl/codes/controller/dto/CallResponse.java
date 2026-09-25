package cl.codes.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import cl.codes.model.Call;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public record CallResponse(
        Long id, String createdAt, String transcription, String operationalSummary, String priority,
        Map<String, Integer> scores, List<String> highlightedWords, String address, Double latitude, Double longitude,
        boolean assigned, String assignedOperator, String assignmentDate, String closureDate, String closureComment, String institution
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static CallResponse de(Call call) {
        List<String> words;
        try {
            words = call.getHighlightedWords() == null
                    ? List.of()
                    : MAPPER.readValue(call.getHighlightedWords(), List.class);
        } catch (Exception e) {
            words = List.of();
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
                call.getDetectedAddress(),
                call.getLatitude(),
                call.getLongitude(),
                call.isAssigned(),
                call.getAssignedOperator(),
                call.getAssignmentDate() != null ? call.getAssignmentDate().toString() : null,
                call.getClosureDate() != null ? call.getClosureDate().toString() : null,
                call.getClosureComment(),
                call.getInstitution()
        );
    }
}


