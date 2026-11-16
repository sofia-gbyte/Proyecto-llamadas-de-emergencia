package cl.codes.service;

import cl.codes.exception.EmailDeliveryException;
import cl.codes.model.User;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodesMailServiceTest {
    @Test
    void sendsBrandedVerificationMessageWithEmbeddedLogoAndPlainTextFallback() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        CodesMailService service = new CodesMailService(sender, "codes@example.test", true);
        User user = new User();
        user.setUsername("operator");
        user.setFirstName("Ana");
        user.setEmail("ana@example.test");

        service.sendVerificationEmail(user, "http://localhost:8000/?verifyToken=abc123");
        message.saveChanges();

        verify(sender).send(message);
        assertEquals("CODES | Confirma tu correo", message.getSubject());
        assertEquals("codes@example.test", message.getFrom()[0].toString());
        assertTrue(message.getContentType().toLowerCase().contains("multipart"));
        StringBuilder content = new StringBuilder();
        collectText(message, content);
        assertTrue(content.toString().contains("cid:codes-logo"));
        assertTrue(content.toString().contains("Confirma tu dirección de correo"));
        assertTrue(content.toString().contains("Ana"));
        assertTrue(content.toString().contains("mismo equipo"));
        assertTrue(content.toString().contains("copia este enlace"));
        assertTrue(content.toString().contains("verifyToken=abc123"));
        assertTrue(findInlineLogo((Multipart) message.getContent()));
    }

    @Test
    void refusesToSendWhenExternalServicesAreDisabled() {
        JavaMailSender sender = mock(JavaMailSender.class);
        CodesMailService service = new CodesMailService(sender, "codes@example.test", false);

        assertThrows(EmailDeliveryException.class, () ->
                service.sendPasswordResetEmail(new User(), "http://localhost:8000/?resetToken=abc"));

        verifyNoInteractions(sender);
    }

    private static void collectText(Part part, StringBuilder content) throws Exception {
        if (part.isMimeType("text/*")) {
            content.append(part.getContent());
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectText(multipart.getBodyPart(i), content);
            }
        }
    }

    private static boolean findInlineLogo(Multipart multipart) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            Part part = multipart.getBodyPart(i);
            String[] contentIds = part.getHeader("Content-ID");
            String[] dispositions = part.getHeader("Content-Disposition");
            if (contentIds != null && String.join("", contentIds).contains("codes-logo")
                    && part.getContentType().toLowerCase().contains("image/png")
                    && dispositions != null && String.join("", dispositions).contains("inline")) {
                return true;
            }
            if (part.isMimeType("multipart/*") && findInlineLogo((Multipart) part.getContent())) return true;
        }
        return false;
    }
}
