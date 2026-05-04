package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.OptionalSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface OptionalSubjectRepository extends JpaRepository<OptionalSubject, Long> {

    List<OptionalSubject> findByGroupIdAndSchoolId(Long groupId, Long schoolId);

    @Transactional
    void deleteByGroupIdAndSchoolId(Long groupId, Long schoolId);
}
