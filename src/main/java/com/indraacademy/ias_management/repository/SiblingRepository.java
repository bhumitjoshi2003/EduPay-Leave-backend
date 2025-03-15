package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Sibling;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SiblingRepository extends JpaRepository<Sibling, Long> {

    List<Sibling> findByStudentId(String studentId);
}