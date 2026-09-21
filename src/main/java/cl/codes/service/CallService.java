package cl.codes.service;

import cl.codes.model.Call;
import cl.codes.repository.CallRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class CallService {

    private final CallRepository repo;

    private static final Map<String, Integer> PRIORITY_ORDER = Map.of(
            "URGENTE", 0, "ROJA", 1, "MEDIA", 2, "VERDE", 3
    );

    public CallService(CallRepository repo) {
        this.repo = repo;
    }

    public List<Call> getPending() {
        List<Call> calls = repo.findByAssignedFalse();
        calls.sort(
                Comparator.<Call, Integer>comparing(call -> PRIORITY_ORDER.getOrDefault(call.getPriority(), 4))
                        .thenComparing(Call::getCreatedAt)
        );
        return calls;
    }

    public List<Call> getInProgress() {
        List<Call> calls = repo.findByAssignedTrueAndClosureDateIsNull();
        calls.sort(Comparator.comparing(Call::getAssignmentDate).reversed());
        return calls;
    }

    public List<Call> getClosed(int limit) {
        List<Call> calls = repo.findByClosureDateIsNotNullOrderByClosureDateDesc();
        return calls.size() > limit ? calls.subList(0, limit) : calls;
    }

    public Call assign(Long id, String operator) {
        Call call = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Call not found"));
        if (call.isAssigned()) {
            throw new IllegalStateException("This call was already assigned");
        }
        call.setAssigned(true);
        call.setAssignedOperator(operator.strip());
        call.setAssignmentDate(LocalDateTime.now());
        return repo.save(call);
    }

    public Call close(Long id, String comment) {
        Call call = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Call not found"));
        if (!call.isAssigned()) {
            throw new IllegalStateException("This case cannot be closed before assignment");
        }
        call.setClosureDate(LocalDateTime.now());
        call.setClosureComment(comment.strip());
        return repo.save(call);
    }

    public record Metrics(
            long totalCalls,
            long activeUrgentCalls,
            long pending,
            long inProgress,
            Double averageResponseSeconds
    ) {}

    public Metrics metrics() {
        long total = repo.count();
        long activeUrgentCalls = repo.countByPriorityAndClosureDateIsNull("URGENTE");
        long pending = repo.countByAssignedFalse();
        long inProgress = repo.countByAssignedTrueAndClosureDateIsNull();

        List<Call> withAssignment = repo.findByAssignmentDateIsNotNull();
        var averageOptional = withAssignment.stream()
                .filter(call -> call.getCreatedAt() != null && call.getAssignmentDate() != null)
                .mapToLong(call -> Duration.between(call.getCreatedAt(), call.getAssignmentDate()).getSeconds())
                .average();
        Double averageResponse = averageOptional.isPresent()
                ? Math.round(averageOptional.getAsDouble() * 10.0) / 10.0
                : null;

        return new Metrics(total, activeUrgentCalls, pending, inProgress, averageResponse);
    }
}


