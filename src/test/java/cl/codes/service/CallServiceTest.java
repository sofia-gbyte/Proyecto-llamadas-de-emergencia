package cl.codes.service;

import cl.codes.model.Call;
import cl.codes.model.User;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import cl.codes.model.CallInformationUpdate;
import cl.codes.controller.dto.CallResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallServiceTest {
    @Mock CallRepository callRepository;
    @Mock UserRepository userRepository;
    @Mock GeocoderService geocoder;

    private CallService service;
    private Authentication operatorAuth;
    private Authentication otherAuth;
    private Authentication adminAuth;

    @BeforeEach
    void setUp() {
        service = new CallService(callRepository, userRepository, geocoder, new ObjectMapper());
        operatorAuth = new UsernamePasswordAuthenticationToken("ana", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        otherAuth = new UsernamePasswordAuthenticationToken("beatriz", null, List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
        adminAuth = new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")));
    }

    private User user(String username, String role, String institution) {
        User u = new User(); u.setUsername(username); u.setRole(role); u.setInstitution(institution); u.setActive(true); return u;
    }

    @Test
    void pendingIsFilteredByInstitution() {
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user("ana", "operator", "bomberos")));
        when(callRepository.findByAssignedFalseAndDeletedFalseAndInstitution("bomberos")).thenReturn(new java.util.ArrayList<>());
        assertTrue(service.getPending(operatorAuth).isEmpty());
        verify(callRepository).findByAssignedFalseAndDeletedFalseAndInstitution("bomberos");
        verify(callRepository, never()).findByAssignedFalseAndDeletedFalseAndInstitution("carabineros");
    }

    @Test
    void metricsKeepTheirExistingSpanishJsonKeys() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new CallService.Metrics(10, 2, 3, 5, 12.5));

        assertTrue(json.contains("\"urgentesActivas\":2"));
        assertTrue(json.contains("\"pendientes\":3"));
        assertTrue(json.contains("\"enProgreso\":5"));
        assertTrue(json.contains("\"tiempoPromedioRespuestaSeg\":12.5"));
    }

    @Test
    void operatorCannotCloseCallFromAnotherInstitution() {
        Call call = new Call(); call.setInstitution("carabineros"); call.setAssigned(true); call.setAssignedOperator("ana");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user("ana", "operator", "bomberos")));
        when(callRepository.findById(7L)).thenReturn(Optional.of(call));
        assertThrows(IllegalArgumentException.class, () -> service.close(7L, "ok", operatorAuth));
        verify(callRepository, never()).save(any());
    }

    @Test
    void assignedOperatorCanCloseOwnCall() {
        Call call = new Call(); call.setInstitution("bomberos"); call.setAssigned(true); call.setAssignedOperator("ana");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user("ana", "operator", "bomberos")));
        when(callRepository.findById(8L)).thenReturn(Optional.of(call));
        when(callRepository.save(call)).thenReturn(call);
        assertSame(call, service.close(8L, "situación controlada", operatorAuth));
        assertNotNull(call.getClosureDate());
        assertEquals("situación controlada", call.getClosureComment());
    }

    @Test
    void operatorCannotCloseAnotherOperatorsCall() {
        Call call = new Call(); call.setInstitution("bomberos"); call.setAssigned(true); call.setAssignedOperator("beatriz");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user("ana", "operator", "bomberos")));
        when(callRepository.findById(9L)).thenReturn(Optional.of(call));
        assertThrows(AccessDeniedException.class, () -> service.close(9L, "ok", operatorAuth));
        verify(callRepository, never()).save(any());
    }

    @Test
    void supervisorCanCloseWithinOwnInstitution() {
        Call call = new Call(); call.setInstitution("bomberos"); call.setAssigned(true); call.setAssignedOperator("ana");
        when(userRepository.findByUsername("beatriz")).thenReturn(Optional.of(user("beatriz", "supervisor", "bomberos")));
        when(callRepository.findById(10L)).thenReturn(Optional.of(call));
        when(callRepository.save(call)).thenReturn(call);
        assertDoesNotThrow(() -> service.close(10L, "supervisión", otherAuth));
    }

    @Test
    void supervisorCanCorrectOpenCallDetailsWithinInstitution() {
        Call call = new Call();
        call.setInstitution("bomberos");
        when(userRepository.findByUsername("beatriz")).thenReturn(Optional.of(user("beatriz", "supervisor", "bomberos")));
        when(callRepository.findById(11L)).thenReturn(Optional.of(call));
        when(geocoder.geocode("Av. Central 123", "Incendio confirmado"))
                .thenReturn(new GeocoderService.Coordinates(-33.44, -70.66, "Av. Central 123"));
        when(callRepository.save(call)).thenReturn(call);

        assertSame(call, service.update(11L, "Incendio confirmado", "Av. Central 123", otherAuth));

        assertEquals("Incendio confirmado", call.getOperationalSummary());
        assertEquals("Av. Central 123", call.getDetectedAddress());
        assertEquals(-33.44, call.getLatitude());
        assertEquals(-70.66, call.getLongitude());
    }

    @Test
    void closedCallCannotBeEdited() {
        Call call = new Call();
        call.setInstitution("bomberos");
        call.setClosureDate(java.time.LocalDateTime.now());
        when(userRepository.findByUsername("beatriz")).thenReturn(Optional.of(user("beatriz", "supervisor", "bomberos")));
        when(callRepository.findById(12L)).thenReturn(Optional.of(call));

        assertThrows(IllegalStateException.class, () -> service.update(12L, "Corrección", null, otherAuth));
        verify(callRepository, never()).save(any());
    }

    @Test
    void assignedOperatorCanAppendInformationIdempotentlyAndRefreshClassification() throws Exception {
        Call call = new Call();
        call.setInstitution("bomberos");
        call.setAssigned(true);
        call.setAssignedOperator("ana");
        call.setTranscription("Incendio en una vivienda.");
        call.setOperationalSummary("Incendio reportado.");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user("ana", "operator", "bomberos")));
        when(callRepository.findById(20L)).thenReturn(Optional.of(call));
        when(callRepository.saveAndFlush(call)).thenReturn(call);

        Call updated = service.appendInformation(
                20L, "ec6de66d-c7d4-4d38-97ad-1afbf15ecf00", "Hay una persona inconsciente.", operatorAuth);
        Call duplicateContent = service.appendInformation(
                20L, "38fce4e0-f62e-474d-8be8-8cfeffeff322", "  HAY UNA PERSONA INCONSCIENTE.  ", operatorAuth);
        Call retry = service.appendInformation(
                20L, "ec6de66d-c7d4-4d38-97ad-1afbf15ecf00", "Hay una persona inconsciente.", operatorAuth);

        assertSame(call, updated);
        assertSame(call, duplicateContent);
        assertSame(call, retry);
        assertEquals(1, new ObjectMapper().readValue(call.getInformationUpdates(),
                new com.fasterxml.jackson.core.type.TypeReference<List<CallInformationUpdate>>() {}).size());
        assertEquals("URGENTE", call.getPriority());
        assertTrue(call.getOperationalSummary().contains("Hay una persona inconsciente"));
        CallResponse response = CallResponse.from(call);
        assertEquals(1, response.informationUpdates().size());
        assertTrue(response.suggestedInstitutions().containsAll(List.of("bomberos", "samu")));
        assertTrue(response.incidentCategories().contains("UNCONSCIOUS_PERSON"));
        assertEquals(response.suggestedInstitutions().size(),
                response.suggestedInstitutions().stream().distinct().count());
        verify(callRepository, times(1)).saveAndFlush(call);
    }

    @Test
    void operatorCannotAppendInformationToAnotherOperatorsCall() {
        Call call = new Call();
        call.setInstitution("bomberos");
        call.setAssigned(true);
        call.setAssignedOperator("otra-persona");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user("ana", "operator", "bomberos")));
        when(callRepository.findById(21L)).thenReturn(Optional.of(call));

        assertThrows(AccessDeniedException.class, () -> service.appendInformation(
                21L, "ec6de66d-c7d4-4d38-97ad-1afbf15ecf00", "Actualización legítima", operatorAuth));
        verify(callRepository, never()).saveAndFlush(any());
    }

    @Test
    void administratorCanArchiveOnlyAnUnassignedOpenCall() {
        Call call = new Call();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user("admin", "administrator", null)));
        when(callRepository.findById(13L)).thenReturn(Optional.of(call));
        when(callRepository.save(call)).thenReturn(call);

        service.deletePending(13L, adminAuth);

        assertTrue(call.isDeleted());
        verify(callRepository).save(call);
    }

    @Test
    void assignedCallCannotBeArchived() {
        Call call = new Call();
        call.setAssigned(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user("admin", "administrator", null)));
        when(callRepository.findById(14L)).thenReturn(Optional.of(call));

        assertThrows(IllegalStateException.class, () -> service.deletePending(14L, adminAuth));
        verify(callRepository, never()).save(any());
    }

    @Test
    void administratorCanArchiveAllActiveCallsAcrossStatusesAndKeepsAlreadyArchivedCallsUntouched() {
        Call pending = new Call();
        Call inProgress = new Call();
        inProgress.setAssigned(true);
        Call closed = new Call();
        closed.setClosureDate(LocalDateTime.now());
        Call archived = new Call();
        archived.setDeleted(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user("admin", "administrator", null)));
        when(callRepository.findAll()).thenReturn(List.of(pending, inProgress, closed, archived));
        when(callRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        int archivedCount = service.archiveAllActiveCalls(adminAuth);

        assertEquals(3, archivedCount);
        assertTrue(pending.isDeleted());
        assertTrue(inProgress.isDeleted());
        assertTrue(closed.isDeleted());
        assertTrue(archived.isDeleted());
        verify(callRepository).saveAll(List.of(pending, inProgress, closed));
    }

    @Test
    void nonAdministratorCannotArchiveAllCalls() {
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user("ana", "operator", "bomberos")));

        assertThrows(AccessDeniedException.class, () -> service.archiveAllActiveCalls(operatorAuth));
        verify(callRepository, never()).findAll();
        verify(callRepository, never()).saveAll(anyList());
    }
}
