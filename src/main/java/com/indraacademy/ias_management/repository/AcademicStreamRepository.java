package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AcademicStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AcademicStreamRepository extends JpaRepository<AcademicStream, Long> {
    boolean existsByStreamName(String streamName);
}
