package cl.codes.repository;

import cl.codes.model.Call;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallRepository extends JpaRepository<Call, Long> {

    List<Call> findByAssignedFalse();

    List<Call> findByAssignedTrueAndClosureDateIsNull();

    List<Call> findByClosureDateIsNotNullOrderByClosureDateDesc();

    long countByAssignedFalse();

    long countByAssignedTrueAndClosureDateIsNull();

    long countByPriorityAndClosureDateIsNull(String priority);

    List<Call> findByAssignmentDateIsNotNull();
}


