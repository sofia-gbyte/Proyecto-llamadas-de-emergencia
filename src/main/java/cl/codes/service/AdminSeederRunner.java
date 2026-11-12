package cl.codes.service;

import cl.codes.config.LocalSecretBootstrap;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AdminSeederRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeederRunner.class);

    private final UserRepository repo;
    private final SecurityService securityService;
    private final String adminUsername;
    private final String adminPassword;

    public AdminSeederRunner(
            UserRepository repo,
            SecurityService securityService,
            @Value("${app.admin-bootstrap-user:}") String adminUsername,
            @Value("${app.admin-bootstrap-password:}") String adminPassword
    ) {
        this.repo = repo;
        this.securityService = securityService;
        this.adminUsername = LocalSecretBootstrap.resolveOrGenerate(
                "CODES_ADMIN_USER",
                adminUsername,
                () -> "admin"
        );
        this.adminPassword = LocalSecretBootstrap.resolveOrGenerate(
                "CODES_ADMIN_PASSWORD",
                adminPassword,
                AdminSeederRunner::generateSecurePassword
        );
    }

    @Override
    public void run(String... args) {
        if (adminUsername.isBlank() || adminPassword.isBlank()) {
            log.warn("No users are registered and no valid bootstrap admin could be created.");
            return;
        }

        if (adminPassword.length() < 12) {
            log.warn("The initial admin password is shorter than 12 characters. Consider using a longer one.");
        }

        var existingAdmin = repo.findByUsername(adminUsername);
        if (existingAdmin.isPresent()) {
            log.debug("Administrator user '{}' already exists; leaving it untouched.", adminUsername);
            return;
        }

        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(securityService.hashPassword(adminPassword));
        admin.setRole("administrator");
        admin.setActive(true);
        admin.setFirstName("Administrator");
        admin.setLastName("CODES");
        admin.setEmail(adminUsername.contains("@") ? adminUsername : null);
        repo.save(admin);

        log.info("Initial administrator user '{}' created from the local bootstrap configuration.", adminUsername);
    }

    private static String generateSecurePassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%^&*+-_=";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(20);
        String[] required = {"A", "a", "1", "!"};
        for (String ch : required) {
            password.append(ch);
        }
        while (password.length() < 20) {
            password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        for (int i = password.length() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = password.charAt(i);
            password.setCharAt(i, password.charAt(j));
            password.setCharAt(j, tmp);
        }
        return password.toString();
    }
}


