package cl.codes.repository;

import cl.codes.model.Call;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CallRepository extends JpaRepository<Call, Long> {
    List<Call> findTop100ByCreatedAtBeforeOrderByCreatedAtAsc(LocalDateTime createdAt);

    List<Call> findByAssignedFalseAndDeletedFalseAndInstitution(String institution);
    List<Call> findByAssignedTrueAndClosureDateIsNullAndDeletedFalseAndInstitution(String institution);
    List<Call> findByClosureDateIsNotNullAndDeletedFalseAndInstitutionOrderByClosureDateDesc(String institution);
    long countByInstitutionAndDeletedFalse(String institution);
    long countByPriorityAndClosureDateIsNullAndInstitutionAndDeletedFalse(String priority, String institution);
    long countByAssignedFalseAndInstitutionAndDeletedFalse(String institution);
    long countByAssignedTrueAndClosureDateIsNullAndInstitutionAndDeletedFalse(String institution);
    long countByDeletedFalse();
    List<Call> findByAssignmentDateIsNotNullAndDeletedFalseAndInstitution(String institution);
    List<Call> findByAssignmentDateIsNotNullAndDeletedFalse();
    List<Call> findByInstitutionIsNullAndCreatedByUserIsNotNullAndDeletedFalse();

    @Query("select c from Call c where c.deleted = false and c.assigned = false and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    List<Call> findPendingVisibleToInstitution(@Param("institution") String institution);

    @Query("select c from Call c where c.deleted = false and c.assigned = true and c.closureDate is null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    List<Call> findInProgressVisibleToInstitution(@Param("institution") String institution);

    @Query("select c from Call c where c.deleted = false and c.closureDate is not null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%')) order by c.closureDate desc")
    List<Call> findClosedVisibleToInstitution(@Param("institution") String institution);

    @Query("select count(c) from Call c where c.deleted = false and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))")
    long countVisibleToInstitution(@Param("institution") String institution);

    @Query("select count(c) from Call c where c.deleted = false and c.priority = :priority and c.closureDate is null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    long countPriorityVisibleToInstitution(@Param("priority") String priority, @Param("institution") String institution);

    @Query("select count(c) from Call c where c.deleted = false and c.assigned = false and c.closureDate is null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    long countPendingVisibleToInstitution(@Param("institution") String institution);

    @Query("select count(c) from Call c where c.deleted = false and c.assigned = true and c.closureDate is null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    long countInProgressVisibleToInstitution(@Param("institution") String institution);

    @Query("select c from Call c where c.deleted = false and c.assignmentDate is not null and (lower(c.institution) = lower(:institution) or c.suggestedInstitutions like concat('%', :institution, '%'))" )
    List<Call> findAssignedVisibleToInstitution(@Param("institution") String institution);
}
