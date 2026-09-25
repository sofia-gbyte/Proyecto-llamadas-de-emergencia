package cl.codes.repository;

import cl.codes.model.Call;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallRepository extends JpaRepository<Call, Long> {
    List<Call> findByAssignedFalseAndInstitution(String institution);
    List<Call> findByAssignedTrueAndClosureDateIsNullAndInstitution(String institution);
    List<Call> findByClosureDateIsNotNullAndInstitutionOrderByClosureDateDesc(String institution);
    long countByInstitution(String institution);
    long countByPriorityAndClosureDateIsNullAndInstitution(String priority, String institution);
    long countByAssignedFalseAndInstitution(String institution);
    long countByAssignedTrueAndClosureDateIsNullAndInstitution(String institution);
    List<Call> findByAssignmentDateIsNotNullAndInstitution(String institution);
    List<Call> findByAssignmentDateIsNotNull();
    List<Call> findByInstitutionIsNullAndCreatedByUserIsNotNull();
}
