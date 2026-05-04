package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.School;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SchoolRepository extends JpaRepository<School, Long> {
    Optional<School> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
