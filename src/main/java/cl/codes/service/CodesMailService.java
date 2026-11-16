package cl.codes.service;

import cl.codes.exception.EmailDeliveryException;
import cl.codes.model.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class CodesMailService {
    private static final String LOGO_CONTENT_ID = "codes-logo";
    private static final String BRAND_NAME = "CODES · Centro de Operaciones y Despacho de Emergencias";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final boolean externalServicesEnabled;

    public CodesMailService(
            JavaMailSender mailSender,
            @Value("${app.mail-from:${spring.mail.username:}}") String fromAddress,
            @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.externalServicesEnabled = externalServicesEnabled;
    }

    public boolean isEnabled() {
        return externalServicesEnabled;
    }

    public void sendVerificationEmail(User user, String link) {
        send(user, "Confirma tu correo", "Confirma tu dirección de correo",
                "Para continuar con el registro de tu cuenta CODES, confirma que esta dirección de correo te pertenece.",
                link, "Confirmar correo", "El enlace vence en 24 horas.",
                "Después de confirmar el correo, un administrador deberá aprobar la cuenta antes de que puedas iniciar sesión.");
    }

    public void sendPasswordResetEmail(User user, String link) {
        send(user, "Recuperación de contraseña", "Recupera tu contraseña",
                "Recibimos una solicitud para restablecer la contraseña de tu cuenta CODES.",
                link, "Restablecer contraseña", "El enlace vence en 15 minutos.",
                "Si no solicitaste este cambio, ignora este mensaje. Tu contraseña actual seguirá vigente.");
    }

    private void send(User user, String subject, String heading, String introduction,
                      String link, String buttonLabel, String expiry, String note) {
        if (!externalServicesEnabled) {
            throw new EmailDeliveryException("El envío de correo está desactivado.");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_RELATED, StandardCharsets.UTF_8.name());
            if (fromAddress != null && !fromAddress.isBlank()) helper.setFrom(fromAddress);
            helper.setTo(user.getEmail());
            helper.setSubject("CODES | " + subject);
            helper.setText(plainText(user, introduction, link, expiry, note),
                    html(user, heading, introduction, link, buttonLabel, expiry, note));
            helper.addInline(LOGO_CONTENT_ID, new ClassPathResource("static/codes-logo.png"), "image/png");
            mailSender.send(message);
        } catch (MessagingException | MailException exception) {
            throw new EmailDeliveryException("No se pudo enviar el correo de CODES.", exception);
        }
    }

    private String plainText(User user, String introduction, String link, String expiry, String note) {
        return "Estimado/a " + recipientName(user) + ":\n\n"
                + introduction + "\n\n"
                + link + "\n\n"
                + expiry + " Abre el enlace en el mismo equipo donde se ejecuta CODES.\n\n"
                + note + "\n\n"
                + BRAND_NAME;
    }

    private String html(User user, String heading, String introduction, String link,
                        String buttonLabel, String expiry, String note) {
        return """
                <!doctype html>
                <html lang="es">
                <body style="margin:0;padding:32px 16px;background:#0a1428;font-family:Arial,Helvetica,sans-serif;color:#e8eefb">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="max-width:620px;margin:0 auto;border:1px solid #2a3f60;border-radius:14px;background:#0f1c30;overflow:hidden">
                    <tr>
                      <td style="padding:22px 30px;background:#14233c;border-bottom:1px solid #2a3f60">
                        <table role="presentation" cellspacing="0" cellpadding="0" border="0">
                          <tr>
                            <td style="padding-right:14px;vertical-align:middle">
                              <img src="cid:%s" width="48" height="48" alt="Símbolo CODES" style="display:block;width:48px;height:48px;border:0;border-radius:9px">
                            </td>
                            <td style="vertical-align:middle">
                              <p style="margin:0;color:#f5f8ff;font-size:19px;font-weight:bold;letter-spacing:2px">CODES</p>
                              <p style="margin:4px 0 0;color:#92a7cf;font-size:10px;letter-spacing:1px;text-transform:uppercase">Centro de operaciones y despacho</p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                    <tr><td style="padding:30px">
                      <p style="margin:0 0 10px;color:#00c8b0;font-size:11px;font-weight:bold;letter-spacing:1.4px;text-transform:uppercase">Seguridad de la cuenta</p>
                      <h1 style="margin:0 0 20px;color:#f5f8ff;font-size:25px;line-height:1.3">%s</h1>
                      <p style="margin:0 0 14px;color:#c5d2e8;font-size:15px;line-height:1.7">Estimado/a %s:</p>
                      <p style="margin:0 0 24px;color:#c5d2e8;font-size:15px;line-height:1.7">%s</p>
                      <p style="margin:0 0 24px">
                        <a href="%s" style="display:inline-block;padding:13px 22px;background:#00c8b0;color:#0a1428;text-decoration:none;border-radius:7px;font-size:14px;font-weight:bold">%s</a>
                      </p>
                      <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="margin:0 0 20px;border-left:3px solid #00c8b0;background:#14233c">
                        <tr><td style="padding:12px 14px;color:#b8c8e2;font-size:13px;line-height:1.6">%s Abre el enlace en el mismo equipo donde se ejecuta CODES.</td></tr>
                      </table>
                      <p style="margin:0 0 8px;color:#92a7cf;font-size:13px;line-height:1.6">Si el botón no funciona, copia este enlace en el navegador:</p>
                      <p style="margin:0 0 20px;word-break:break-all;font-size:12px;line-height:1.6"><a href="%s" style="color:#00c8b0;text-decoration:underline">%s</a></p>
                      <p style="margin:0;color:#92a7cf;font-size:13px;line-height:1.6">%s</p>
                    </td></tr>
                    <tr><td style="padding:16px 30px;background:#091120;border-top:1px solid #2a3f60;color:#7185a5;font-size:11px;line-height:1.6">%s<br>Notificación automática de CODES. Por favor, no respondas a este correo.</td></tr>
                  </table>
                </body>
                </html>
                """.formatted(LOGO_CONTENT_ID, escapeHtml(heading), escapeHtml(recipientName(user)),
                escapeHtml(introduction), escapeHtml(link), escapeHtml(buttonLabel), escapeHtml(expiry),
                escapeHtml(link), escapeHtml(link), escapeHtml(note), escapeHtml(BRAND_NAME));
    }

    private String recipientName(User user) {
        return user.getFirstName() == null || user.getFirstName().isBlank()
                ? user.getUsername() : user.getFirstName().strip();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
