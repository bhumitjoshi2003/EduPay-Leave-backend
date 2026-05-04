package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ExamConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamConfigRepository extends JpaRepository<ExamConfig, Long> {

    List<ExamConfig> findBySchoolId(Long schoolId);

    List<ExamConfig> findBySessionAndClassNameAndSchoolId(String session, String className, Long schoolId);

    List<ExamConfig> findBySessionAndSchoolId(String session, Long schoolId);

    List<ExamConfig> findByClassNameAndSchoolId(String className, Long schoolId);

    boolean existsBySessionAndClassNameAndExamNameAndSchoolId(String session, String className, String examName, Long schoolId);
}
