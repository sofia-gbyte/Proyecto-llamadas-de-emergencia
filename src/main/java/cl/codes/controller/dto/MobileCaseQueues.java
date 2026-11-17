package cl.codes.controller.dto;

import java.util.List;

public record MobileCaseQueues(
        List<MobileCaseResponse> pending,
        List<MobileCaseResponse> inProgress
) {
}
