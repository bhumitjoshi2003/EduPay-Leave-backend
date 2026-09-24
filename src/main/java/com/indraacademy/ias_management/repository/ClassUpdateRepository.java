package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ClassUpdate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClassUpdateRepository extends JpaRepository<ClassUpdate, Long> {

    Optional<ClassUpdate> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsByAttachmentObjectKey(String attachmentObjectKey);

    List<ClassUpdate> findBySchoolIdAndTeacherIdOrderByCreatedAtDescIdDesc(Long schoolId, String teacherId,
                                                                          Pageable pageable);

    /**
     * A student's class: whole-class updates (section_id NULL) plus their own section's, that
     * have not expired, newest first.
     */
    @Query("SELECT u FROM ClassUpdate u WHERE u.schoolId = :schoolId AND u.academicSessionId = :sessionId "
            + "AND u.classId = :classId AND (u.sectionId IS NULL OR u.sectionId = :sectionId) "
            + "AND (u.expiresAt IS NULL OR u.expiresAt > :now) ORDER BY u.createdAt DESC, u.id DESC")
    List<ClassUpdate> findActiveForClass(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                         @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                         @Param("now") Instant now, Pageable pageable);
}
