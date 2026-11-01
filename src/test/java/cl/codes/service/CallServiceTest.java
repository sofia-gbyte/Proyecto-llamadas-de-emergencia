package cl.codes.service;

import cl.codes.model.Call;
import cl.codes.model.User;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
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
        service = new CallService(callRepository, userRepository, geocoder);
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
}
