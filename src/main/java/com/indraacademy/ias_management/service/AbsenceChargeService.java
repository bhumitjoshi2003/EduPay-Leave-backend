package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.repository.AbsenceChargeSettlementRepository;
import com.indraacademy.ias_management.repository.AttendanceRow;
import com.indraacademy.ias_management.repository.StudentAttendanceRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * The per-absence "unapplied leave" fee, formerly the legacy attendance table's charge_paid flag.
 *
 * <p>A <b>chargeable absence</b> is an Attendance V2 ABSENT row, within the fee session's dates,
 * with no APPROVED leave for that student on that date, and not yet settled. The fee flow reads
 * {@link #countChargeable} (× the existing per-absence rate) into the checkout quote and payment
 * order exactly as before, and after a successful payment {@link #settleAfterPayment} records a
 * settlement for every absence chargeable at that moment — the same "a payment clears everything
 * outstanding for the session" behaviour charge_paid had. Settlement lives in its own table, so
 * attendance rows describe attendance only.
 *
 * <p>Differences from the legacy flag, by design: only APPROVED leave waives the charge (the old
 * pre-fill also waived pending/rejected leave), and leave approved later waives a not-yet-paid
 * absence automatically, because chargeability is evaluated when the fee is quoted rather than
 * frozen when attendance was marked.
 */
@Service
public class AbsenceChargeService {
    private static final Logger log = LoggerFactory.getLogger(AbsenceChargeService.class);

    private final StudentAttendanceRepository rows;
    private final AbsenceChargeSettlementRepository settlements;
    private final AttendanceService attendance;
    private final AcademicSessionService academicSessions;
    private final SecurityUtil security;
    private final AuditService audit;
    private final Clock clock;

    public AbsenceChargeService(StudentAttendanceRepository rows, AbsenceChargeSettlementRepository settlements,
                                AttendanceService attendance, AcademicSessionService academicSessions,
                                SecurityUtil security, AuditService audit, Clock clock) {
        this.rows = rows;
        this.settlements = settlements;
        this.attendance = attendance;
        this.academicSessions = academicSessions;
        this.security = security;
        this.audit = audit;
        this.clock = clock;
    }

    /** Chargeable absences for a student in a fee session, in the caller's own school. */
    @Transactional(readOnly = true)
    public long countChargeable(String studentId, String sessionLabel) {
        Long schoolId = security.getSchoolId();
        if (schoolId == null) return 0L;
        return countChargeable(schoolId, studentId, sessionLabel);
    }

    @Transactional(readOnly = true)
    public long countChargeable(Long schoolId, String studentId, String sessionLabel) {
        return chargeableAbsenceIds(schoolId, studentId, sessionLabel).size();
    }

    /**
     * Marks every currently chargeable absence of the student in that session as settled — called
     * after a fee payment succeeds (online settlement or an admin's manual payment). Takes an
     * explicit, already-validated schoolId because the online settlement path runs without an
     * ambient school context (webhook recovery). Idempotent.
     */
    @Transactional
    public int settleAfterPayment(String studentId, String sessionLabel, Long schoolId, String ipAddress) {
        if (schoolId == null || studentId == null || studentId.isBlank() || sessionLabel == null || sessionLabel.isBlank()) {
            log.warn("Skipping absence-charge settlement: missing school, student or session.");
            return 0;
        }
        List<Long> ids = chargeableAbsenceIds(schoolId, studentId, sessionLabel);
        Instant now = clock.instant();
        int settled = 0;
        for (Long id : ids) settled += settlements.settle(schoolId, id, now);
        if (settled > 0) {
            String user = security.getUsername();
            String role = security.getRole();
            audit.log(user != null ? user : "SYSTEM", role != null ? role : "SYSTEM", "SETTLE_ABSENCE_CHARGES",
                    "AbsenceChargeSettlement", studentId + "_" + sessionLabel, null,
                    settled + " absence charge(s) settled", ipAddress != null ? ipAddress : "SYSTEM");
        }
        return settled;
    }

    private List<Long> chargeableAbsenceIds(Long schoolId, String studentId, String sessionLabel) {
        if (studentId == null || studentId.isBlank() || sessionLabel == null || sessionLabel.isBlank()) return List.of();
        Optional<AcademicSession> session = academicSessions.getSessionByLabel(schoolId, sessionLabel);
        if (session.isEmpty()) {
            log.warn("No AcademicSession for school {} label '{}' — no absence charges.", schoolId, sessionLabel);
            return List.of();
        }
        List<AttendanceRow> absences = rows.findStudentRowsWithStatus(schoolId, studentId, AttendanceStatus.ABSENT,
                session.get().getStartDate(), session.get().getEndDate());
        if (absences.isEmpty()) return List.of();
        Set<String> approvedLeave = attendance.approvedLeaveKeysForStudent(schoolId, studentId,
                session.get().getStartDate(), session.get().getEndDate());
        List<Long> candidates = absences.stream()
                .filter(r -> !approvedLeave.contains(AttendanceService.leaveKey(r.studentId(), r.date())))
                .map(AttendanceRow::studentAttendanceId)
                .toList();
        if (candidates.isEmpty()) return List.of();
        Set<Long> settled = new HashSet<>(settlements.findSettledIds(schoolId, candidates));
        return candidates.stream().filter(id -> !settled.contains(id)).toList();
    }
}
