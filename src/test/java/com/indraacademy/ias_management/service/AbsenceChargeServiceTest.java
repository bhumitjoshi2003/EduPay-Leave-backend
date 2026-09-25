package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.repository.AbsenceChargeSettlementRepository;
import com.indraacademy.ias_management.repository.AttendanceRow;
import com.indraacademy.ias_management.repository.StudentAttendanceRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The unapplied-absence fee: ABSENT rows without APPROVED leave that are not yet settled. */
@ExtendWith(MockitoExtension.class)
class AbsenceChargeServiceTest {

    private static final long SCHOOL = 5L;
    private static final String STUDENT = "S1", SESSION = "2026-2027";
    private static final LocalDate START = LocalDate.of(2026, 4, 1), END = LocalDate.of(2027, 3, 31);
    private static final Instant NOW = Instant.parse("2026-09-24T20:00:00Z");

    @Mock private StudentAttendanceRepository rows;
    @Mock private AbsenceChargeSettlementRepository settlements;
    @Mock private AttendanceService attendance;
    @Mock private AcademicSessionService academicSessions;
    @Mock private SecurityUtil security;
    @Mock private AuditService audit;
    private AbsenceChargeService service;

    @BeforeEach
    void setUp() {
        service = new AbsenceChargeService(rows, settlements, attendance, academicSessions, security, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void sessionWithAbsences(AttendanceRow... absences) {
        AcademicSession session = new AcademicSession();
        session.setStartDate(START);
        session.setEndDate(END);
        when(academicSessions.getSessionByLabel(SCHOOL, SESSION)).thenReturn(Optional.of(session));
        when(rows.findStudentRowsWithStatus(SCHOOL, STUDENT, AttendanceStatus.ABSENT, START, END)).thenReturn(List.of(absences));
    }

    @Test
    void approvedLeaveAndSettledAbsencesAreNotChargeable() {
        sessionWithAbsences(absent(1L, "2026-06-01"), absent(2L, "2026-06-02"), absent(3L, "2026-06-03"));
        when(attendance.approvedLeaveKeysForStudent(SCHOOL, STUDENT, START, END))
                .thenReturn(Set.of(AttendanceService.leaveKey(STUDENT, LocalDate.parse("2026-06-02"))));
        when(settlements.findSettledIds(eq(SCHOOL), eq(List.of(1L, 3L)))).thenReturn(List.of(3L));

        assertThat(service.countChargeable(SCHOOL, STUDENT, SESSION)).isOne();
    }

    @Test
    void settleRecordsEveryChargeableAbsenceAndAuditsOnce() {
        sessionWithAbsences(absent(1L, "2026-06-01"), absent(2L, "2026-06-02"));
        when(attendance.approvedLeaveKeysForStudent(SCHOOL, STUDENT, START, END)).thenReturn(Set.of());
        when(settlements.findSettledIds(eq(SCHOOL), anyList())).thenReturn(List.of());
        when(settlements.settle(eq(SCHOOL), anyLong(), eq(NOW))).thenReturn(1);

        assertThat(service.settleAfterPayment(STUDENT, SESSION, SCHOOL, "1.2.3.4")).isEqualTo(2);

        verify(settlements).settle(SCHOOL, 1L, NOW);
        verify(settlements).settle(SCHOOL, 2L, NOW);
        verify(audit).log(eq("SYSTEM"), eq("SYSTEM"), eq("SETTLE_ABSENCE_CHARGES"), eq("AbsenceChargeSettlement"),
                eq(STUDENT + "_" + SESSION), isNull(), eq("2 absence charge(s) settled"), eq("1.2.3.4"));
    }

    @Test
    void nothingChargeableWithoutSchoolOrSession() {
        when(security.getSchoolId()).thenReturn(null);
        assertThat(service.countChargeable(STUDENT, SESSION)).isZero();

        when(academicSessions.getSessionByLabel(SCHOOL, "1999-2000")).thenReturn(Optional.empty());
        assertThat(service.countChargeable(SCHOOL, STUDENT, "1999-2000")).isZero();
        assertThat(service.settleAfterPayment(STUDENT, SESSION, null, null)).isZero();
        verifyNoInteractions(rows, settlements, audit);
    }

    private static AttendanceRow absent(long id, String date) {
        return new AttendanceRow(id, SCHOOL, STUDENT, LocalDate.parse(date), AttendanceStatus.ABSENT, 9L, null);
    }
}
