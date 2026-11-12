package cl.codes.service;

import cl.codes.model.Call;
import cl.codes.repository.CallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CallRetentionService {
    public record ExpiredCall(Long id, String originalAudio) {}

    private final CallRepository callRepository;

    public CallRetentionService(CallRepository callRepository) {
        this.callRepository = callRepository;
    }

    @Transactional
    public List<ExpiredCall> deleteExpiredBatch(LocalDateTime createdBefore) {
        List<Call> expiredCalls = callRepository.findTop100ByCreatedAtBeforeOrderByCreatedAtAsc(createdBefore);
        if (expiredCalls.isEmpty()) {
            return List.of();
        }

        List<ExpiredCall> expiredCallAudio = expiredCalls.stream()
                .map(call -> new ExpiredCall(call.getId(), call.getOriginalAudio()))
                .toList();
        callRepository.deleteAllInBatch(expiredCalls);
        return expiredCallAudio;
    }
}
