package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AiFeeReminderBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiFeeReminderBatchRepository extends JpaRepository<AiFeeReminderBatch, Long> {

    Optional<AiFeeReminderBatch> findByWorkflowId(String workflowId);

    /** Row-locked read for the dispatch step's check-then-update — see AiWorkflowController.dispatch(). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AiFeeReminderBatch b where b.workflowId = :workflowId")
    Optional<AiFeeReminderBatch> findByWorkflowIdForUpdate(@Param("workflowId") String workflowId);
}
