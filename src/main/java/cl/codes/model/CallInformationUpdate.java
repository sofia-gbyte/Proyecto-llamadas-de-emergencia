package cl.codes.model;

public record CallInformationUpdate(
        String updateId,
        String information,
        String createdAt,
        String createdBy
) {}
