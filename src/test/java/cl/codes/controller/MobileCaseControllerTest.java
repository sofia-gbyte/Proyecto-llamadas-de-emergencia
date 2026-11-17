package cl.codes.controller;

import cl.codes.controller.dto.CloseRequest;
import cl.codes.model.Call;
import cl.codes.service.AuditLogService;
import cl.codes.service.CallService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobileCaseControllerTest {
    private CallService callService;
    private AuditLogService auditLogService;
    private MobileCaseController controller;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        callService = mock(CallService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new MobileCaseController(callService, auditLogService);
        auth = new UsernamePasswordAuthenticationToken("ana", null, List.of());
    }

    @Test
    void returnsPendingCasesAndOnlyTheCurrentOperatorsAssignments() {
        Call pending = call(1L, false, null, null);
        Call assignedToCurrentUser = call(2L, true, "ana", null);
        Call assignedToAnotherUser = call(3L, true, "beatriz", null);
        Call closed = call(4L, false, null, LocalDateTime.now());
        when(callService.getPending(auth)).thenReturn(List.of(pending));
        when(callService.getInProgress(auth)).thenReturn(
                List.of(assignedToCurrentUser, assignedToAnotherUser, closed));

        var queues = controller.cases(auth);

        assertEquals(List.of(1L), queues.pending().stream().map(item -> item.id()).toList());
        assertEquals(List.of(2L), queues.inProgress().stream().map(item -> item.id()).toList());
        verify(callService).getPending(auth);
        verify(callService).getInProgress(auth);
    }

    @Test
    void mobileCaseResponseOmitsTranscriptionAndAudioData() throws Exception {
        Call call = call(7L, false, null, null);
        call.setTranscription("Transcripción privada");
        call.setOriginalAudio("audio-secreto.wav");
        when(callService.getById(7L, auth)).thenReturn(call);

        var details = controller.caseDetails(7L, auth);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(details);

        assertEquals(7L, details.id());
        assertFalse(json.contains("Transcripción privada"));
        assertFalse(json.contains("audio-secreto.wav"));
    }

    @Test
    void refusesToExposeAnotherOperatorsCase() {
        Call call = call(9L, true, "beatriz", null);
        when(callService.getById(9L, auth)).thenReturn(call);

        assertThrows(cl.codes.exception.ResourceNotFoundException.class,
                () -> controller.caseDetails(9L, auth));
    }

    @Test
    void closesOnlyTheCurrentOperatorsAssignedCaseAndAuditsIt() {
        Call assigned = call(11L, true, "ana", null);
        Call closed = call(11L, true, "ana", LocalDateTime.now());
        when(callService.getById(11L, auth)).thenReturn(assigned);
        when(callService.close(11L, "Resuelto", auth)).thenReturn(closed);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.25");

        var response = controller.close(11L, new CloseRequest("Resuelto"), auth, request);

        assertEquals(11L, response.id());
        verify(callService).close(11L, "Resuelto", auth);
        verify(auditLogService).recordEvent("ana", "mobile-close-call", "call",
                "11", "192.168.1.25", "success");
    }

    private static Call call(Long id, boolean assigned, String operator, LocalDateTime closureDate) {
        Call call = new Call();
        call.setId(id);
        call.setCreatedAt(LocalDateTime.of(2026, 11, 16, 12, 0));
        call.setOperationalSummary("Resumen operativo");
        call.setPriority("ROJA");
        call.setDetectedAddress("Dirección de prueba");
        call.setLatitude(-33.4);
        call.setLongitude(-70.6);
        call.setAssigned(assigned);
        call.setAssignedOperator(operator);
        call.setClosureDate(closureDate);
        return call;
    }
}
