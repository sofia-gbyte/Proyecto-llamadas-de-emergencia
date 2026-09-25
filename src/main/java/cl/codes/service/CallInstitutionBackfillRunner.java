package cl.codes.service;

import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class CallInstitutionBackfillRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CallInstitutionBackfillRunner.class);
    private final CallRepository callRepository;
    private final UserRepository userRepository;

    public CallInstitutionBackfillRunner(CallRepository callRepository, UserRepository userRepository) {
        this.callRepository = callRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        int updated = 0;
        for (var call : callRepository.findByInstitutionIsNullAndCreatedByUserIsNotNull()) {
            userRepository.findByUsername(call.getCreatedByUser()).ifPresent(user -> {
                if (user.getInstitution() != null && !user.getInstitution().isBlank()) {
                    call.setInstitution(user.getInstitution());
                    callRepository.save(call);
                }
            });
            if (call.getInstitution() != null) updated++;
        }
        if (updated > 0) log.info("Backfilled institution on {} existing calls.", updated);
    }
}
