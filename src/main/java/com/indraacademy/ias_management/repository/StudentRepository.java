package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, String> {
    List<Student> findByClassName(String className);

    List<Student> findByClassNameAndStatus(String className, StudentStatus status);

    List<Student> findByStatus(StudentStatus status);

    @Modifying
    @Query("""
           update Student s
           set s.status = 'UPCOMING'
           where s.joiningDate > :today
           """)
    void updateStatusUpcoming(@Param("today") LocalDate today);

    @Modifying
    @Query("""
           update Student s
           set s.status = 'INACTIVE'
           where s.leavingDate is not null
             and s.leavingDate <= :today
           """)
    void updateStatusInactive(@Param("today") LocalDate today);

    @Modifying
    @Query("""
           update Student s
           set s.status = 'ACTIVE'
           where (s.joiningDate is null or s.joiningDate <= :today)
             and (s.leavingDate is null or s.leavingDate > :today)
           """)
    void updateStatusActive(@Param("today") LocalDate today);
}