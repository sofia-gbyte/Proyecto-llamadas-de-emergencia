package cl.codes.controller.dto;

import cl.codes.model.Call;

import java.time.LocalDateTime;

public record MobileCaseResponse(
        Long id,
        String createdAt,
        String operationalSummary,
        String priority,
        String address,
        Double latitude,
        Double longitude,
        boolean assigned,
        String assignedOperator,
        String assignmentDate,
        String institution
) {
    public static MobileCaseResponse from(Call call) {
        return new MobileCaseResponse(
                call.getId(),
                format(call.getCreatedAt()),
                call.getOperationalSummary(),
                call.getPriority(),
                call.getDetectedAddress(),
                call.getLatitude(),
                call.getLongitude(),
                call.isAssigned(),
                call.getAssignedOperator(),
                format(call.getAssignmentDate()),
                call.getInstitution()
        );
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
