package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ReportCardRemark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportCardRemarkRepository extends JpaRepository<ReportCardRemark, Long> {

    Optional<ReportCardRemark> findByStudentIdAndTemplateIdAndSessionAndRemarkTypeAndSchoolId(
            String studentId, Long templateId, String session, String remarkType, Long schoolId);

    List<ReportCardRemark> findByTemplateIdAndSessionAndSchoolId(
            Long templateId, String session, Long schoolId);

    List<ReportCardRemark> findByStudentIdAndTemplateIdAndSessionAndSchoolId(
            String studentId, Long templateId, String session, Long schoolId);
}
