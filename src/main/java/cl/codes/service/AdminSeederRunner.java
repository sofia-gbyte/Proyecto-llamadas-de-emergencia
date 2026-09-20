package cl.codes.service;

import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

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
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return;
        }

        if (adminUsername.isBlank() || adminPassword.isBlank()) {
            log.warn("No users are registered and the environment variables CODES_ADMIN_USER and CODES_ADMIN_PASSWORD are not defined.");
            return;
        }

        if (adminPassword.length() < 12) {
            log.warn("The initial admin password is shorter than 12 characters. Consider using a longer one.");
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

        log.info("Initial administrator user '{}' created from environment variables.", adminUsername);
    }
}


