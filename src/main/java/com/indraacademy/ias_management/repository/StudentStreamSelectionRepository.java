package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentStreamSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentStreamSelectionRepository extends JpaRepository<StudentStreamSelection, Long> {

    Optional<StudentStreamSelection> findByStudentIdAndSchoolId(String studentId, Long schoolId);

    /** Batch-load selections for multiple students in a single query. */
    List<StudentStreamSelection> findByStudentIdInAndSchoolId(Collection<String> studentIds, Long schoolId);

    @Transactional
    void deleteByStudentIdAndSchoolId(String studentId, Long schoolId);
}
