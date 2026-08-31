package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Parent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ParentRepository extends JpaRepository<Parent, String>, JpaSpecificationExecutor<Parent> {
    Optional<Parent> findByParentIdAndSchoolId(String parentId, Long schoolId);
    List<Parent> findBySchoolIdOrderByNameAsc(Long schoolId);
    boolean existsByPhoneNumberAndSchoolId(String phoneNumber, Long schoolId);
    boolean existsByEmailIgnoreCaseAndSchoolId(String email, Long schoolId);
    long countBySchoolId(Long schoolId);
    long countBySchoolIdAndActiveTrue(Long schoolId);
}
