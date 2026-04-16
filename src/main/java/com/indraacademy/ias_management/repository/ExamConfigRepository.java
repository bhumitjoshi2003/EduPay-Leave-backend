package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ExamConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamConfigRepository extends JpaRepository<ExamConfig, Long> {
    List<ExamConfig> findBySessionAndClassName(String session, String className);
    List<ExamConfig> findBySession(String session);
    List<ExamConfig> findByClassName(String className);
    boolean existsBySessionAndClassNameAndExamName(String session, String className, String examName);
}
