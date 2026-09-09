package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.TimetableCorrectionRequest;
import com.indraacademy.ias_management.entity.TimetableCorrectionRequest.Status;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface TimetableCorrectionRequestRepository extends JpaRepository<TimetableCorrectionRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TimetableCorrectionRequest r where r.id = :id and r.schoolId = :schoolId")
    Optional<TimetableCorrectionRequest> lockById(Long id, Long schoolId);
    List<TimetableCorrectionRequest> findBySchoolIdAndStatusOrderByCreatedAtDesc(Long schoolId, Status status);
    List<TimetableCorrectionRequest> findBySchoolIdAndRequestedByTeacherIdOrderByCreatedAtDesc(Long schoolId, String teacherId);
    boolean existsBySchoolIdAndTimetableEntryIdAndRequestedByTeacherIdAndStatus(Long schoolId, Long entryId, String teacherId, Status status);
}
