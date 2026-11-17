package cl.codes.controller;

import cl.codes.controller.dto.CloseRequest;
import cl.codes.controller.dto.MobileCaseQueues;
import cl.codes.controller.dto.MobileCaseResponse;
import cl.codes.exception.ResourceNotFoundException;
import cl.codes.model.Call;
import cl.codes.service.AuditLogService;
import cl.codes.service.CallService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mobile")
public class MobileCaseController {
    private final CallService callService;
    private final AuditLogService auditLogService;

    public MobileCaseController(CallService callService, AuditLogService auditLogService) {
        this.callService = callService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/cases")
    @PreAuthorize("hasRole('MOBILE_OPERATOR')")
    public MobileCaseQueues cases(Authentication auth) {
        List<MobileCaseResponse> pending = callService.getPending(auth).stream()
                .filter(call -> !call.isAssigned() && call.getClosureDate() == null)
                .map(MobileCaseResponse::from)
                .toList();
        List<MobileCaseResponse> inProgress = callService.getInProgress(auth).stream()
                .filter(call -> call.isAssigned()
                        && auth.getName().equals(call.getAssignedOperator())
                        && call.getClosureDate() == null)
                .map(MobileCaseResponse::from)
                .toList();
        return new MobileCaseQueues(pending, inProgress);
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasRole('MOBILE_OPERATOR')")
    public MobileCaseResponse caseDetails(@PathVariable Long id, Authentication auth) {
        return MobileCaseResponse.from(getAccessibleActiveCase(id, auth));
    }

    @PostMapping("/cases/{id}/assign")
    @PreAuthorize("hasRole('MOBILE_OPERATOR')")
    public MobileCaseResponse assign(
            @PathVariable Long id,
            Authentication auth,
            HttpServletRequest request
    ) {
        Call call = callService.assign(id, auth);
        auditLogService.recordEvent(auth.getName(), "mobile-assign-call", "call",
                String.valueOf(id), request.getRemoteAddr(), "success");
        return MobileCaseResponse.from(call);
    }

    @PostMapping("/cases/{id}/close")
    @PreAuthorize("hasRole('MOBILE_OPERATOR')")
    public MobileCaseResponse close(
            @PathVariable Long id,
            @Valid @RequestBody CloseRequest requestBody,
            Authentication auth,
            HttpServletRequest request
    ) {
        Call call = getAccessibleActiveCase(id, auth);
        if (!call.isAssigned() || !auth.getName().equals(call.getAssignedOperator())) {
            throw new AccessDeniedException("Solo el operador asignado puede cerrar este caso.");
        }
        call = callService.close(id, requestBody.comment(), auth);
        auditLogService.recordEvent(auth.getName(), "mobile-close-call", "call",
                String.valueOf(id), request.getRemoteAddr(), "success");
        return MobileCaseResponse.from(call);
    }

    private Call getAccessibleActiveCase(Long id, Authentication auth) {
        Call call = callService.getById(id, auth);
        if (call.getClosureDate() != null) {
            throw new ResourceNotFoundException("Call not found");
        }
        if (call.isAssigned() && !auth.getName().equals(call.getAssignedOperator())) {
            throw new ResourceNotFoundException("Call not found");
        }
        return call;
    }
}
