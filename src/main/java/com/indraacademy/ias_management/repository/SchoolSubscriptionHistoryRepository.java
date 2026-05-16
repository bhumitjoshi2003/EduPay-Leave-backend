package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolSubscriptionHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchoolSubscriptionHistoryRepository extends JpaRepository<SchoolSubscriptionHistory, Long> {

    /** Most-recent events first, up to the given page size. */
    List<SchoolSubscriptionHistory> findBySchoolIdOrderByOccurredAtDesc(Long schoolId, Pageable pageable);
}
