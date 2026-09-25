package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AbsenceChargeSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AbsenceChargeSettlementRepository extends JpaRepository<AbsenceChargeSettlement, Long> {

    @Query("SELECT c.studentAttendanceId FROM AbsenceChargeSettlement c WHERE c.schoolId = :schoolId "
            + "AND c.studentAttendanceId IN :ids")
    List<Long> findSettledIds(@Param("schoolId") Long schoolId, @Param("ids") Collection<Long> studentAttendanceIds);

    /**
     * Idempotent: settling an already-settled absence is a no-op, so a repeated or concurrent
     * payment settlement can never fail (and roll back the payment) on the unique constraint.
     */
    @Modifying
    @Query(value = "INSERT INTO absence_charge_settlement (school_id, student_attendance_id, settled_at) "
            + "VALUES (:schoolId, :studentAttendanceId, :settledAt) "
            + "ON CONFLICT (student_attendance_id) DO NOTHING", nativeQuery = true)
    int settle(@Param("schoolId") Long schoolId, @Param("studentAttendanceId") Long studentAttendanceId,
               @Param("settledAt") Instant settledAt);
}
