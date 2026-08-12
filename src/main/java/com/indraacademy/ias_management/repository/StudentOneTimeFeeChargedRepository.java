package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentOneTimeFeeCharged;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface StudentOneTimeFeeChargedRepository extends JpaRepository<StudentOneTimeFeeCharged, Long> {

    List<StudentOneTimeFeeCharged> findBySchoolIdAndStudentId(Long schoolId, String studentId);

    boolean existsBySchoolIdAndStudentIdAndFeeHeadId(Long schoolId, String studentId, Long feeHeadId);

    /** Explicit JPQL rather than a derived-projection method name: Spring Data's
     * find-property-By-... naming convention resolves this to a query whose row mapper
     * Hibernate 6.6 then fails to reconcile against the declared Set&lt;Long&gt; return type
     * once the table actually has a matching row (QueryTypeMismatchException — "Result type
     * is 'Long' but the query returned a 'StudentOneTimeFeeCharged'"). An empty result never
     * exercises the row mapper, which is why this only surfaces once a ONE_TIME fee head has
     * actually been charged to the student — e.g. every recalculation call after generation's
     * own first write. Selecting the column explicitly removes the ambiguity entirely. */
    @Query("SELECT s.feeHeadId FROM StudentOneTimeFeeCharged s WHERE s.schoolId = :schoolId AND s.studentId = :studentId")
    Set<Long> findFeeHeadIdBySchoolIdAndStudentId(@Param("schoolId") Long schoolId, @Param("studentId") String studentId);
}
