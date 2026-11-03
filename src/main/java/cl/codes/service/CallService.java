package cl.codes.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import cl.codes.classifier.Classifier;
import cl.codes.model.CallInformationUpdate;
import cl.codes.model.Call;
import cl.codes.model.User;
import cl.codes.exception.ResourceNotFoundException;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class CallService {
    private final CallRepository repo;
    private final UserRepository userRepo;
    private final GeocoderService geocoder;
    private final ObjectMapper mapper;
    private static final int MAX_INFORMATION_UPDATES = 100;
    private static final int MAX_TOTAL_INFORMATION_CHARS = 20_000;

    private static final Map<String, Integer> PRIORITY_ORDER = Map.of(
            "URGENTE", 0, "ROJA", 1, "MEDIA", 2, "VERDE", 3
    );

    public CallService(CallRepository repo, UserRepository userRepo, GeocoderService geocoder, ObjectMapper mapper) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.geocoder = geocoder;
        this.mapper = mapper;
    }

    public Call getById(Long id, Authentication auth) {
        User user = currentUser(auth);
        Call call = repo.findById(Objects.requireNonNull(id)).filter(existing -> !existing.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Call not found"));
        ensureSameInstitution(user, call);
        return call;
    }

    public Call update(Long id, String operationalSummary, String address, Authentication auth) {
        User user = currentUser(auth);
        ensureNotPreview(user);
        Call call = getById(id, auth);
        if (call.getClosureDate() != null) {
            throw new IllegalStateException("Closed calls cannot be edited");
        }
        String normalizedSummary = operationalSummary.strip();
        String normalizedAddress = address == null || address.isBlank() ? null : address.strip();
        if (!Objects.equals(normalizedAddress, call.getDetectedAddress())) {
            GeocoderService.Coordinates coordinates = geocoder.geocode(normalizedAddress, normalizedSummary);
            String resolvedAddress = coordinates.formattedAddress();
            call.setDetectedAddress(resolvedAddress == null || resolvedAddress.isBlank()
                    ? normalizedAddress
                    : resolvedAddress);
            call.setLatitude(coordinates.lat());
            call.setLongitude(coordinates.lng());
        }
        call.setOperationalSummary(normalizedSummary);
        return repo.save(call);
    }

    @Transactional
    public Call appendInformation(Long id, String updateId, String information, Authentication auth) {
        User user = currentUser(auth);
        ensureNotPreview(user);
        Call call = getById(id, auth);
        if (call.getClosureDate() != null) {
            throw new IllegalStateException("Closed calls cannot be updated");
        }
        boolean supervisorOrAdmin = "supervisor".equalsIgnoreCase(user.getRole()) || isAdmin(user);
        if (!supervisorOrAdmin && (!call.isAssigned() || !user.getUsername().equals(call.getAssignedOperator()))) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the assigned operator or a supervisor can update this call");
        }

        String normalizedInformation = information.strip();
        List<CallInformationUpdate> updates = readInformationUpdates(call.getInformationUpdates());
        if (updates.stream().anyMatch(update -> update.updateId().equalsIgnoreCase(updateId))) {
            return call;
        }
        String normalizedForComparison = normalizeInformation(normalizedInformation);
        if (updates.stream().anyMatch(update ->
                normalizeInformation(update.information()).equals(normalizedForComparison))) {
            return call;
        }
        if (updates.size() >= MAX_INFORMATION_UPDATES) {
            throw new IllegalStateException("This call has reached the maximum number of information updates");
        }
        int currentInformationLength = updates.stream().mapToInt(update -> update.information().length()).sum();
        if (currentInformationLength + normalizedInformation.length() > MAX_TOTAL_INFORMATION_CHARS) {
            throw new IllegalArgumentException("The total additional information for this call cannot exceed 20000 characters");
        }

        updates = new ArrayList<>(updates);
        updates.add(new CallInformationUpdate(updateId, normalizedInformation, LocalDateTime.now().toString(), user.getUsername()));
        try {
            call.setInformationUpdates(mapper.writeValueAsString(updates));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize call information updates", e);
        }

        String combinedText = classificationText(call, updates);
        Classifier.ClassificationResult classification = Classifier.classifyCall(combinedText);
        call.setPriority(classification.priority());
        call.setUrgentScore(classification.scores().getOrDefault("urgente", 0));
        call.setRedScore(classification.scores().getOrDefault("roja", 0));
        call.setMediumScore(classification.scores().getOrDefault("media", 0));
        call.setGreenScore(classification.scores().getOrDefault("verde", 0));
        try {
            call.setHighlightedWords(mapper.writeValueAsString(classification.highlightedWords()));
            call.setClassificationReasons(mapper.writeValueAsString(classification.reasons()));
            call.setSuggestedInstitutions(mapper.writeValueAsString(classification.suggestedInstitutions()));
            call.setInstitutionRecommendations(mapper.writeValueAsString(classification.institutionRecommendations()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize call classification", e);
        }

        String summary = call.getOperationalSummary();
        String addition = "Información complementaria: " + normalizedInformation;
        call.setOperationalSummary(summary == null || summary.isBlank()
                ? addition
                : summary.stripTrailing() + "\n" + addition);
        return repo.saveAndFlush(call);
    }

    private List<CallInformationUpdate> readInformationUpdates(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored call information updates are invalid", e);
        }
    }

    private String classificationText(Call call, List<CallInformationUpdate> updates) {
        String transcription = call.getTranscription() == null ? "" : call.getTranscription();
        String additionalInformation = updates.stream()
                .map(CallInformationUpdate::information)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return transcription + (additionalInformation.isBlank() ? "" : "\n" + additionalInformation);
    }

    private String normalizeInformation(String information) {
        return information.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public void deletePending(Long id, Authentication auth) {
        User user = currentUser(auth);
        ensureNotPreview(user);
        if (!isAdmin(user)) {
            throw new org.springframework.security.access.AccessDeniedException("Only an administrator can remove a call");
        }
        Call call = repo.findById(Objects.requireNonNull(id)).orElseThrow(() -> new ResourceNotFoundException("Call not found"));
        if (call.isDeleted()) return;
        if (call.isAssigned() || call.getClosureDate() != null) {
            throw new IllegalStateException("Only unassigned open calls can be removed");
        }
        call.setDeleted(true);
        repo.save(call);
    }

    private User currentUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw new IllegalStateException("Authentication required");
        return userRepo.findByUsername(auth.getName()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private boolean isAdmin(User user) { return "administrator".equalsIgnoreCase(user.getRole()); }

    private void ensureSameInstitution(User user, Call call) {
        if (isAdmin(user)) return;
        if (!isVisibleToInstitution(user, call)) {
            throw new ResourceNotFoundException("Call not found");
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
        List<Call> calls = isAdmin(user) ? new java.util.ArrayList<>(repo.findAll().stream().filter(c -> !c.isDeleted() && !c.isAssigned() && c.getClosureDate() == null).toList()) : mergeVisible(
            repo.findByAssignedFalseAndDeletedFalseAndInstitution(user.getInstitution()),
            repo.findPendingVisibleToInstitution(user.getInstitution()));
        return sorted(calls);
    }

    public List<Call> getInProgress(Authentication auth) {
        User user = currentUser(auth);
        List<Call> calls = isAdmin(user) ? new java.util.ArrayList<>(repo.findAll().stream().filter(c -> !c.isDeleted() && c.isAssigned() && c.getClosureDate() == null).toList()) : mergeVisible(
            repo.findByAssignedTrueAndClosureDateIsNullAndDeletedFalseAndInstitution(user.getInstitution()),
            repo.findInProgressVisibleToInstitution(user.getInstitution()));
        calls.sort(Comparator.comparing((Call call) -> call.getAssignmentDate(), Comparator.nullsLast(Comparator.reverseOrder())));
        return calls;
    }

    public List<Call> getClosed(int limit, Authentication auth) {
        User user = currentUser(auth);
        List<Call> calls = isAdmin(user) ? repo.findAll().stream().filter(c -> !c.isDeleted() && c.getClosureDate() != null).sorted(Comparator.comparing((Call call) -> call.getClosureDate(), Comparator.nullsLast(Comparator.reverseOrder()))).toList() : mergeVisible(
            repo.findByClosureDateIsNotNullAndDeletedFalseAndInstitutionOrderByClosureDateDesc(user.getInstitution()),
            repo.findClosedVisibleToInstitution(user.getInstitution()));
        return calls.size() > limit ? calls.subList(0, limit) : calls;
    }

    private List<Call> sorted(List<Call> calls) {
        calls.sort(Comparator.<Call, Integer>comparing(call -> PRIORITY_ORDER.getOrDefault(call.getPriority(), 4)).thenComparing(call -> call.getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder())));
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
        Call call = getById(id, auth);
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
        Call call = getById(id, auth);
        if (!call.isAssigned()) throw new IllegalStateException("This case cannot be closed before assignment");
        if (call.getClosureDate() != null) throw new IllegalStateException("This case is already closed");
        boolean supervisorOrAdmin = "supervisor".equalsIgnoreCase(user.getRole()) || isAdmin(user);
        if (!supervisorOrAdmin && !user.getUsername().equals(call.getAssignedOperator())) throw new org.springframework.security.access.AccessDeniedException("Only the assigned operator or a supervisor can close this call");
        call.setClosureDate(LocalDateTime.now());
        call.setClosureComment(comment.strip());
        return repo.save(call);
    }

    public record Metrics(
            long totalCalls,
            @JsonProperty("urgentesActivas") long activeUrgentCalls,
            @JsonProperty("pendientes") long pending,
            @JsonProperty("enProgreso") long inProgress,
            @JsonProperty("tiempoPromedioRespuestaSeg") Double averageResponseTimeSeconds
    ) {}

    public Metrics metrics(Authentication auth) {
        User user = currentUser(auth);
        String institution = user.getInstitution();
        long total, activeUrgentCalls, pending, inProgress;
        List<Call> withAssignment;
        if (isAdmin(user)) {
            var calls = repo.findAll().stream().filter(c -> !c.isDeleted()).toList();
            total = repo.countByDeletedFalse();
            activeUrgentCalls = calls.stream().filter(c -> "URGENTE".equals(c.getPriority()) && c.getClosureDate() == null).count();
            pending = calls.stream().filter(c -> !c.isAssigned() && c.getClosureDate() == null).count();
            inProgress = calls.stream().filter(c -> c.isAssigned() && c.getClosureDate() == null).count();
            withAssignment = repo.findByAssignmentDateIsNotNullAndDeletedFalse();
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

}
