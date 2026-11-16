package cl.codes.service;

import cl.codes.controller.dto.RegisterRequest;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRegistrationService {
    private final UserRepository userRepository;
    private final SecurityService securityService;
    private final EmailVerificationService emailVerificationService;

    public AccountRegistrationService(UserRepository userRepository,
                                      SecurityService securityService,
                                      EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.emailVerificationService = emailVerificationService;
    }

    @Transactional
    public User register(RegisterRequest request) {
        String username = request.username().strip();
        String email = request.email().strip().toLowerCase();
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("Ya existe una cuenta con ese nombre de usuario.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("Ya existe una cuenta con ese correo.");
        }

        User account = new User();
        account.setUsername(username);
        account.setPasswordHash(securityService.hashPassword(request.password()));
        account.setRole("operator");
        account.setActive(false);
        account.setEmailVerified(false);
        account.setFirstName(request.firstName().strip());
        account.setLastName(request.lastName().strip());
        account.setEmail(email);
        account.setInstitution(request.institution());

        User savedAccount = userRepository.save(account);
        emailVerificationService.sendVerification(savedAccount);
        return savedAccount;
    }
}
