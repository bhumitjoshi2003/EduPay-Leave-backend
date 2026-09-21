package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.UploadIntent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UploadIntentRepository extends JpaRepository<UploadIntent, Long> {

    /** The completion endpoint's trust anchor — looked up by objectKey (globally unique), never
     * trusted without also checking schoolId/purpose/entityId/status/expiry against the caller's
     * actual authenticated context. */
    Optional<UploadIntent> findByObjectKey(String objectKey);

    /** Orphan-cleanup candidates: never completed, and past expiry by enough of a grace period
     * that a slow-but-legitimate in-flight completion call isn't mistaken for an orphan. Bounded
     * via Pageable so a large backlog is never loaded in one pass. */
    @Query("SELECT i FROM UploadIntent i WHERE i.status = 'PENDING' AND i.expiresAt < :cutoff ORDER BY i.expiresAt ASC")
    List<UploadIntent> findOrphanCandidates(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
