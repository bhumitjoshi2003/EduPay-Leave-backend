package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StreamCoreSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface StreamCoreSubjectRepository extends JpaRepository<StreamCoreSubject, Long> {

    List<StreamCoreSubject> findByStreamIdAndSchoolId(Long streamId, Long schoolId);

    boolean existsByStreamIdAndSubjectNameAndSchoolId(Long streamId, String subjectName, Long schoolId);

    @Transactional
    void deleteByStreamIdAndSchoolId(Long streamId, Long schoolId);
}
