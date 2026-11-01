package cl.codes.repository;

import cl.codes.model.Call;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class CallRepositoryTest {
    @Autowired
    private CallRepository repository;

    @Test
    void operationalQueriesExcludeArchivedCalls() {
        Call active = new Call();
        active.setInstitution("bomberos");
        active.setPriority("ROJA");

        Call archived = new Call();
        archived.setInstitution("bomberos");
        archived.setDeleted(true);

        repository.save(active);
        repository.save(archived);

        assertEquals(1, repository.findByAssignedFalseAndDeletedFalseAndInstitution("bomberos").size());
        assertEquals(1, repository.findPendingVisibleToInstitution("bomberos").size());
        assertEquals(1, repository.countByInstitutionAndDeletedFalse("bomberos"));
        assertEquals(1, repository.countByDeletedFalse());
    }
}
