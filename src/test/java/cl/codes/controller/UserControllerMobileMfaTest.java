package cl.codes.controller;

import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.service.AuditLogService;
import cl.codes.service.SecurityService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class UserControllerMobileMfaTest {
    @Test
    void administratorResetClearsTotpAndRevokesExistingSessions() {
        UserRepository repository = mock(UserRepository.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        UserController controller = new UserController(repository, mock(SecurityService.class), auditLogService);
        User operator = new User();
        operator.setId(7L);
        operator.setUsername("operador");
        operator.setRole("operator");
        operator.setMobileTotpEnabled(true);
        operator.setMobileTotpSecret("encrypted");
        operator.setMobileTotpLastAcceptedStep(123L);
        when(repository.findById(7L)).thenReturn(Optional.of(operator));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.25");
        var administrator = new UsernamePasswordAuthenticationToken("admin", null);

        controller.resetMobileMfa(7L, administrator, request);

        assertFalse(operator.isMobileTotpEnabled());
        assertNull(operator.getMobileTotpSecret());
        assertNull(operator.getMobileTotpLastAcceptedStep());
        assertNotNull(operator.getSessionsValidFrom());
        verify(repository).save(operator);
        verify(auditLogService).recordEvent("admin", "reset-mobile-mfa", "user", "operador",
                "192.168.1.25", "success");
    }

    @Test
    void resetRejectsNonOperatorAndTestAccounts() {
        UserRepository repository = mock(UserRepository.class);
        UserController controller = new UserController(repository, mock(SecurityService.class),
                mock(AuditLogService.class));
        User administratorUser = new User();
        administratorUser.setId(8L);
        administratorUser.setUsername("admin");
        administratorUser.setRole("administrator");
        when(repository.findById(8L)).thenReturn(Optional.of(administratorUser));

        var response = controller.resetMobileMfa(8L,
                new UsernamePasswordAuthenticationToken("root", null), mock(HttpServletRequest.class));

        org.junit.jupiter.api.Assertions.assertEquals(409, response.getStatusCode().value());
        verify(repository, never()).save(any());
    }
}
