package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, String> {
    List<Student> findByClassName(String className);

    List<Student> findByClassNameAndJoiningDateLessThanEqual(String className, LocalDate targetDate);

    List<Student> findByClassNameAndJoiningDateGreaterThan(String className, LocalDate targetDate);
}