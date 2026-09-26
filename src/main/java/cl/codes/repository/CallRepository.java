package cl.codes.repository;

import cl.codes.model.Call;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("select c from Call c where c.assigned = false and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    List<Call> findPendingVisibleToInstitution(@Param("institution") String institution);

    @Query("select c from Call c where c.assigned = true and c.closureDate is null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    List<Call> findInProgressVisibleToInstitution(@Param("institution") String institution);

    @Query("select c from Call c where c.closureDate is not null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%')) order by c.closureDate desc")
    List<Call> findClosedVisibleToInstitution(@Param("institution") String institution);

    @Query("select count(c) from Call c where c.institution = :institution or c.suggestedInstitutions like concat('%', :institution, '%')")
    long countVisibleToInstitution(@Param("institution") String institution);

    @Query("select count(c) from Call c where c.priority = :priority and c.closureDate is null and (c.institution = :institution or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    long countPriorityVisibleToInstitution(@Param("priority") String priority, @Param("institution") String institution);

    @Query("select count(c) from Call c where c.assigned = false and c.closureDate is null and (c.institution = :institution or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    long countPendingVisibleToInstitution(@Param("institution") String institution);

    @Query("select count(c) from Call c where c.assigned = true and c.closureDate is null and (c.institution = :institution or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    long countInProgressVisibleToInstitution(@Param("institution") String institution);

    @Query("select c from Call c where c.assignmentDate is not null and (c.institution = :institution or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    List<Call> findAssignedVisibleToInstitution(@Param("institution") String institution);
}
