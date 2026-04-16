package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentStreamSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface StudentStreamSelectionRepository extends JpaRepository<StudentStreamSelection, Long> {
    Optional<StudentStreamSelection> findByStudentId(String studentId);

    @Transactional
    void deleteByStudentId(String studentId);
}
