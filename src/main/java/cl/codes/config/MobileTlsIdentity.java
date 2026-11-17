package cl.codes.config;

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;

public final class MobileTlsIdentity {
    private static final String KEY_ALIAS = "codes-mobile";
    private static final String KEYSTORE_TYPE = "PKCS12";

    private final Path keyStorePath;
    private final String keyStorePassword;
    private final X509Certificate certificate;

    private MobileTlsIdentity(Path keyStorePath, String keyStorePassword, X509Certificate certificate) {
        this.keyStorePath = keyStorePath;
        this.keyStorePassword = keyStorePassword;
        this.certificate = certificate;
    }

    public static MobileTlsIdentity loadOrCreate(Path keyStorePath, String keyStorePassword) {
        try {
            if (Files.exists(keyStorePath)) {
                return load(keyStorePath, keyStorePassword);
            }
            return create(keyStorePath, keyStorePassword);
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("No se pudo preparar el certificado HTTPS de la app móvil.", exception);
        }
    }

    public Path keyStorePath() {
        return keyStorePath;
    }

    public String keyStorePassword() {
        return keyStorePassword;
    }

    public String fingerprint() {
        try {
            return HexFormat.ofDelimiter(":").withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo calcular la huella del certificado móvil.", exception);
        }
    }

    private static MobileTlsIdentity load(Path keyStorePath, String password)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, password.toCharArray());
        }
        Certificate loaded = keyStore.getCertificate(KEY_ALIAS);
        if (!(loaded instanceof X509Certificate certificate)) {
            throw new GeneralSecurityException("El almacén no contiene un certificado HTTPS CODES válido.");
        }
        certificate.checkValidity();
        return new MobileTlsIdentity(keyStorePath, password, certificate);
    }

    private static MobileTlsIdentity create(Path keyStorePath, String password)
            throws GeneralSecurityException, IOException {
        Files.createDirectories(keyStorePath.getParent());
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        Instant now = Instant.now();
        X509V3CertificateGenerator generator = new X509V3CertificateGenerator();
        generator.setSerialNumber(new BigInteger(128, new SecureRandom()).setBit(0));
        javax.security.auth.x500.X500Principal subject =
                new javax.security.auth.x500.X500Principal("CN=CODES Mobile Local Server");
        generator.setIssuerDN(subject);
        generator.setSubjectDN(subject);
        generator.setNotBefore(Date.from(now.minus(5, ChronoUnit.MINUTES)));
        generator.setNotAfter(Date.from(now.plus(3650, ChronoUnit.DAYS)));
        generator.setPublicKey(keyPair.getPublic());
        generator.setSignatureAlgorithm("SHA256WithRSA");
        generator.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        generator.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        generator.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        generator.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName[]{
                        new GeneralName(GeneralName.dNSName, "localhost"),
                        new GeneralName(GeneralName.iPAddress, "127.0.0.1")
                }));
        X509Certificate certificate = generator.generate(keyPair.getPrivate());
        certificate.verify(keyPair.getPublic());

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(null, password.toCharArray());
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(), password.toCharArray(),
                new Certificate[]{certificate});
        Path temporaryPath = Files.createTempFile(keyStorePath.getParent(), ".codes-mobile-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporaryPath)) {
                keyStore.store(output, password.toCharArray());
            }
            LocalFilePermissions.restrict(temporaryPath, false);
            try {
                Files.move(temporaryPath, keyStorePath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, keyStorePath);
            }
            LocalFilePermissions.restrict(keyStorePath, false);
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
        return new MobileTlsIdentity(keyStorePath, password, certificate);
    }
}
