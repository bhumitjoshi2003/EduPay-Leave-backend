package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.FeeHead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeeHeadRepository extends JpaRepository<FeeHead, Long> {

    List<FeeHead> findBySchoolIdAndActiveTrueOrderByDisplayOrder(Long schoolId);

    List<FeeHead> findBySchoolIdOrderByDisplayOrder(Long schoolId);

    Optional<FeeHead> findBySchoolIdAndCode(Long schoolId, String code);

    Optional<FeeHead> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsBySchoolIdAndCode(Long schoolId, String code);
}
