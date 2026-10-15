package cl.codes.service;

import cl.codes.model.Call;
import cl.codes.model.User;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class CallService {
    private final CallRepository repo;
    private final UserRepository userRepo;

    private static final Map<String, Integer> PRIORITY_ORDER = Map.of(
            "URGENTE", 0, "ROJA", 1, "MEDIA", 2, "VERDE", 3
    );

    public CallService(CallRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    private User currentUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw new IllegalStateException("Authentication required");
        return userRepo.findByUsername(auth.getName()).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private boolean isAdmin(User user) { return "administrator".equalsIgnoreCase(user.getRole()); }

    private void ensureSameInstitution(User user, Call call) {
        if (isAdmin(user)) return;
        if (!isVisibleToInstitution(user, call)) {
            throw new IllegalArgumentException("Call not found");
        }
    }

    private boolean isVisibleToInstitution(User user, Call call) {
        if (user.getInstitution() == null || user.getInstitution().isBlank()) return false;
        if (call.getInstitution() != null && call.getInstitution().equalsIgnoreCase(user.getInstitution())) return true;
        String suggested = call.getSuggestedInstitutions();
        return suggested != null && suggested.toLowerCase().contains('"' + user.getInstitution().toLowerCase() + '"');
    }

    public List<Call> getPending(Authentication auth) {
        User user = currentUser(auth);
        List<Call> calls = isAdmin(user) ? new java.util.ArrayList<>(repo.findAll().stream().filter(c -> !c.isAssigned() && c.getClosureDate() == null).toList()) : mergeVisible(
            repo.findByAssignedFalseAndInstitution(user.getInstitution()),
            repo.findPendingVisibleToInstitution(user.getInstitution()));
        return sorted(calls);
    }

    public List<Call> getInProgress(Authentication auth) {
        User user = currentUser(auth);
        List<Call> calls = isAdmin(user) ? new java.util.ArrayList<>(repo.findAll().stream().filter(c -> c.isAssigned() && c.getClosureDate() == null).toList()) : mergeVisible(
            repo.findByAssignedTrueAndClosureDateIsNullAndInstitution(user.getInstitution()),
            repo.findInProgressVisibleToInstitution(user.getInstitution()));
        calls.sort(Comparator.comparing(Call::getAssignmentDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return calls;
    }

    public List<Call> getClosed(int limit, Authentication auth) {
        User user = currentUser(auth);
        List<Call> calls = isAdmin(user) ? repo.findAll().stream().filter(c -> c.getClosureDate() != null).sorted(Comparator.comparing(Call::getClosureDate, Comparator.nullsLast(Comparator.reverseOrder()))).toList() : mergeVisible(
            repo.findByClosureDateIsNotNullAndInstitutionOrderByClosureDateDesc(user.getInstitution()),
            repo.findClosedVisibleToInstitution(user.getInstitution()));
        return calls.size() > limit ? calls.subList(0, limit) : calls;
    }

    private List<Call> sorted(List<Call> calls) {
        calls.sort(Comparator.<Call, Integer>comparing(call -> PRIORITY_ORDER.getOrDefault(call.getPriority(), 4)).thenComparing(Call::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return calls;
    }

    private List<Call> mergeVisible(List<Call> own, List<Call> suggested) {
        java.util.LinkedHashMap<Long, Call> merged = new java.util.LinkedHashMap<>();
        for (Call call : own) merged.put(call.getId(), call);
        for (Call call : suggested) merged.putIfAbsent(call.getId(), call);
        return new java.util.ArrayList<>(merged.values());
    }

    private void ensureNotPreview(User user) {
        if (user.isTestAccount()) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Esta es una sesión de vista previa (solo lectura): no puede modificar llamadas reales.");
        }
    }

    public Call assign(Long id, Authentication auth) {
        User user = currentUser(auth);
        ensureNotPreview(user);
        Call call = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Call not found"));
        ensureSameInstitution(user, call);
        if (call.isAssigned()) throw new IllegalStateException("This call was already assigned");
        if (call.getClosureDate() != null) throw new IllegalStateException("This call is already closed");
        call.setAssigned(true);
        call.setAssignedOperator(user.getUsername());
        call.setAssignmentDate(LocalDateTime.now());
        return repo.save(call);
    }

    public Call close(Long id, String comment, Authentication auth) {
        User user = currentUser(auth);
        ensureNotPreview(user);
        Call call = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Call not found"));
        ensureSameInstitution(user, call);
        if (!call.isAssigned()) throw new IllegalStateException("This case cannot be closed before assignment");
        if (call.getClosureDate() != null) throw new IllegalStateException("This case is already closed");
        boolean supervisorOrAdmin = "supervisor".equalsIgnoreCase(user.getRole()) || isAdmin(user);
        if (!supervisorOrAdmin && !user.getUsername().equals(call.getAssignedOperator())) throw new org.springframework.security.access.AccessDeniedException("Only the assigned operator or a supervisor can close this call");
        call.setClosureDate(LocalDateTime.now());
        call.setClosureComment(comment.strip());
        return repo.save(call);
    }

    public record Metrics(long totalCalls, long urgentesActivas, long pendientes, long enProgreso, Double tiempoPromedioRespuestaSeg) {}

    public Metrics metrics(Authentication auth) {
        User user = currentUser(auth);
        String institution = user.getInstitution();
        long total, activeUrgentCalls, pending, inProgress;
        List<Call> withAssignment;
        if (isAdmin(user)) {
            total = repo.count();
            activeUrgentCalls = repo.findAll().stream().filter(c -> "URGENTE".equals(c.getPriority()) && c.getClosureDate() == null).count();
            pending = repo.findAll().stream().filter(c -> !c.isAssigned() && c.getClosureDate() == null).count();
            inProgress = repo.findAll().stream().filter(c -> c.isAssigned() && c.getClosureDate() == null).count();
            withAssignment = repo.findByAssignmentDateIsNotNull();
        } else {
            total = repo.countVisibleToInstitution(institution);
            activeUrgentCalls = repo.countPriorityVisibleToInstitution("URGENTE", institution);
            pending = repo.countPendingVisibleToInstitution(institution);
            inProgress = repo.countInProgressVisibleToInstitution(institution);
            withAssignment = repo.findAssignedVisibleToInstitution(institution);
        }
        var averageOptional = withAssignment.stream().filter(call -> call.getCreatedAt() != null && call.getAssignmentDate() != null).mapToLong(call -> Duration.between(call.getCreatedAt(), call.getAssignmentDate()).getSeconds()).average();
        Double averageResponse = averageOptional.isPresent() ? Math.round(averageOptional.getAsDouble() * 10.0) / 10.0 : null;
        return new Metrics(total, activeUrgentCalls, pending, inProgress, averageResponse);
    }

    /* Nombres del record alineados 1 a 1 con lo que espera script.js
       (d.pendientes, d.enProgreso, d.urgentesActivas, d.tiempoPromedioRespuestaSeg):
       antes se llamaban distinto en Java y el JSON nunca calzaba con el
       frontend, por eso el panel RESUMEN se quedaba en "–". */
}
