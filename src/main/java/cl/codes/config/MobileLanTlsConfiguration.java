package cl.codes.config;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class MobileLanTlsConfiguration {
    public static final int HTTPS_PORT = 8443;
    private static final String KEYSTORE_PASSWORD_SETTING = "CODES_MOBILE_TLS_KEYSTORE_PASSWORD";

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> mobileLanConnectorCustomizer(
            @Value("${app.mobile.lan-enabled:false}") boolean enabled,
            @Value("${app.mobile.lan-address:}") String address
    ) {
        if (!enabled) return factory -> {};
        if (!MobileLanNetwork.isMobileIpv4(address)) {
            throw new IllegalStateException("El acceso móvil requiere una IPv4 privada de LAN o Tailscale.");
        }
        boolean available = MobileLanNetwork.availableAddresses().stream()
                .anyMatch(candidate -> candidate.ipAddress().equals(address));
        if (!available) {
            throw new IllegalStateException("La dirección seleccionada para el APK ya no está disponible en este equipo.");
        }

        Path dataDirectory = Path.of(System.getProperty("CODES_DATA_DIR", "data"));
        String password = LocalSecretBootstrap.resolveOrGenerate(
                KEYSTORE_PASSWORD_SETTING, "", MobileLanTlsConfiguration::generatePassword);
        MobileTlsIdentity identity = MobileTlsIdentity.loadOrCreate(
                dataDirectory.resolve(".codes-mobile-keystore.p12"), password);

        return factory -> {
            Connector connector = new Connector(TomcatWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(HTTPS_PORT);
            connector.setScheme("https");
            connector.setSecure(true);
            connector.setProperty("address", address);
            connector.setProperty("SSLEnabled", "true");

            SSLHostConfig sslHostConfig = new SSLHostConfig();
            sslHostConfig.setProtocols("TLSv1.2,+TLSv1.3");
            SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
                    sslHostConfig, SSLHostConfigCertificate.Type.RSA);
            certificate.setCertificateKeystoreFile(identity.keyStorePath().toString());
            certificate.setCertificateKeystorePassword(identity.keyStorePassword());
            certificate.setCertificateKeystoreType("PKCS12");
            sslHostConfig.addCertificate(certificate);
            connector.addSslHostConfig(sslHostConfig);
            factory.addAdditionalConnectors(connector);
        };
    }

    public static MobileTlsIdentity identityForDesktop() {
        Path dataDirectory = Path.of(System.getProperty("CODES_DATA_DIR", "data"));
        String password = LocalSecretBootstrap.resolveOrGenerate(
                KEYSTORE_PASSWORD_SETTING, "", MobileLanTlsConfiguration::generatePassword);
        return MobileTlsIdentity.loadOrCreate(
                dataDirectory.resolve(".codes-mobile-keystore.p12"), password);
    }

    private static String generatePassword() {
        byte[] password = new byte[32];
        new java.security.SecureRandom().nextBytes(password);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(password);
    }
}
