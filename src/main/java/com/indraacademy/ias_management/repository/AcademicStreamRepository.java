package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AcademicStream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AcademicStreamRepository extends JpaRepository<AcademicStream, Long> {

    List<AcademicStream> findBySchoolId(Long schoolId);

    boolean existsByStreamNameAndSchoolId(String streamName, Long schoolId);
}
