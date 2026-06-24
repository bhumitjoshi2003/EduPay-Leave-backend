package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.CoScholasticEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoScholasticEntryRepository extends JpaRepository<CoScholasticEntry, Long> {

    Optional<CoScholasticEntry> findByStudentIdAndTemplateIdAndSessionAndActivityAndSchoolId(
            String studentId, Long templateId, String session, String activity, Long schoolId);

    List<CoScholasticEntry> findByTemplateIdAndSessionAndSchoolId(
            Long templateId, String session, Long schoolId);

    List<CoScholasticEntry> findByStudentIdAndTemplateIdAndSessionAndSchoolId(
            String studentId, Long templateId, String session, Long schoolId);
}
