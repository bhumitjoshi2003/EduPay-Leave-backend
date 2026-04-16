package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ClassSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClassSubjectRepository extends JpaRepository<ClassSubject, Long> {
    List<ClassSubject> findByClassName(String className);
    boolean existsByClassNameAndSubjectName(String className, String subjectName);
}
