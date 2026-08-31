package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Captures the exact HTML body AttendanceReminderService builds and sends, independent of
 * whether the underlying SMTP call actually succeeds — added after live E2E testing hit real
 * "Authentication failed" errors from placeholder dev SMTP credentials, which correctly proves
 * the send *path* works but says nothing about whether the email *content* is correct. This is
 * that missing check: assert on the real rendered HTML via an ArgumentCaptor on EmailService,
 * the same technique used to distinguish "content is right, transport failed" from "content is
 * wrong" without depending on the environment actually having working SMTP.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceReminderServiceTest {

    @Mock private AttendanceService attendanceService;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private EmailService emailService;
    @Mock private SecurityUtil securityUtil;

    private AttendanceReminderService service;

    private static final Long SCHOOL_ID = 2L;
    private static final String SESSION = "2026-2027";
    private static final String STUDENT_ID = "E2E2_STU_001";

    @BeforeEach
    void setUp() {
        service = new AttendanceReminderService();
        ReflectionTestUtils.setField(service, "attendanceService", attendanceService);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
    }

    @Test
    void sendAttendanceReminderEmailsWithOutcomes_buildsCorrectHtmlContent_regardlessOfSendOutcome() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        ClassAttendanceSummaryDTO summaryRow = new ClassAttendanceSummaryDTO(
                STUDENT_ID, "Jordan Test Student", "10", 10L, 3L, 7L, 30.0);
        when(attendanceService.getSchoolSummary("year", null, null, SESSION))
                .thenReturn(List.of(summaryRow));

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setName("Jordan Test Student");
        student.setEmail("jordan.test@example.com");
        student.setStatus(com.indraacademy.ias_management.entity.StudentStatus.ACTIVE);
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(student));

        School school = new School();
        school.setName("E2E Test School 2");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));

        // Content correctness must not depend on whether the SMTP send itself succeeds —
        // mirrors production, where sendHtmlEmailSync can genuinely fail (bad credentials,
        // network) with a perfectly correct email body already built.
        when(emailService.sendHtmlEmailSync(anyString(), anyString(), anyString())).thenReturn(false);

        Map<String, String> outcomes = service.sendAttendanceReminderEmailsWithOutcomes(List.of(STUDENT_ID), SESSION);
        assertThat(outcomes).containsEntry(STUDENT_ID, "failed");

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmailSync(toCaptor.capture(), subjectCaptor.capture(), htmlCaptor.capture());

        assertThat(toCaptor.getValue()).isEqualTo("jordan.test@example.com");
        assertThat(subjectCaptor.getValue()).isEqualTo("Attendance Warning – " + SESSION);

        String html = htmlCaptor.getValue();
        assertThat(html)
                .contains("Jordan Test Student")
                .contains("E2E Test School 2")
                .contains(SESSION)
                .contains("30.0%")
                .contains("3 of 10 working days present")
                .contains("Attendance Warning")
                .doesNotContain("null");
    }

    /**
     * The consecutive-absence batch must NOT tell a parent their child is below the attendance
     * threshold — that student is deliberately at 85% here, which is exactly the case where the
     * standard wording would be a false statement sent to a real family.
     */
    @Test
    void consecutiveAbsenceBatch_statesTheActualStreak_andNeverClaimsTheStudentIsBelowThreshold() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        // Comfortably ABOVE any threshold — flagged purely for three days running.
        ClassAttendanceSummaryDTO summaryRow = new ClassAttendanceSummaryDTO(
                STUDENT_ID, "Jordan Test Student", "10", 20L, 17L, 3L, 85.0);
        when(attendanceService.getSchoolSummary("year", null, null, SESSION))
                .thenReturn(List.of(summaryRow));

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setName("Jordan Test Student");
        student.setEmail("jordan.test@example.com");
        student.setStatus(com.indraacademy.ias_management.entity.StudentStatus.ACTIVE);
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(student));

        School school = new School();
        school.setName("E2E Test School 2");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(emailService.sendHtmlEmailSync(anyString(), anyString(), anyString())).thenReturn(true);

        service.sendAttendanceReminderEmailsWithOutcomes(
                List.of(STUDENT_ID), SESSION,
                Map.of(STUDENT_ID, List.of("2026-08-12", "2026-08-13", "2026-08-14")));

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmailSync(anyString(), anyString(), htmlCaptor.capture());
        String html = htmlCaptor.getValue();

        assertThat(html)
                .contains("3 consecutive school days")
                .contains("12, 13 &amp; 14 Aug")     // collapsed month, human-readable
                .contains("Recent absences:")
                .contains("85.0%")                   // cumulative context still shown
                .doesNotContain("fallen below")      // the false claim, absent
                .doesNotContain("null");
    }

    /** The percentage-selected batch keeps its original wording untouched. */
    @Test
    void thresholdBatch_keepsTheOriginalBelowThresholdWording_andShowsNoAbsenceDatesLine() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        ClassAttendanceSummaryDTO summaryRow = new ClassAttendanceSummaryDTO(
                STUDENT_ID, "Jordan Test Student", "10", 10L, 3L, 7L, 30.0);
        when(attendanceService.getSchoolSummary("year", null, null, SESSION))
                .thenReturn(List.of(summaryRow));

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setName("Jordan Test Student");
        student.setEmail("jordan.test@example.com");
        student.setStatus(com.indraacademy.ias_management.entity.StudentStatus.ACTIVE);
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(student));

        School school = new School();
        school.setName("E2E Test School 2");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(emailService.sendHtmlEmailSync(anyString(), anyString(), anyString())).thenReturn(true);

        service.sendAttendanceReminderEmailsWithOutcomes(List.of(STUDENT_ID), SESSION);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmailSync(anyString(), anyString(), htmlCaptor.capture());
        String html = htmlCaptor.getValue();

        assertThat(html)
                .contains("fallen below the school's required threshold")
                .doesNotContain("consecutive school days")
                .doesNotContain("Recent absences:")
                .doesNotContain("null");
    }

    @Test
    void sendAttendanceReminderEmailsWithOutcomes_studentWithNoAttendanceRow_reportsFailedWithoutCallingEmailService() {
        // No attendance summary row for this student short-circuits before securityUtil.getSchoolId()
        // is ever reached — deliberately not stubbed here (a real school-scoped student lookup
        // never happens on this path).
        when(attendanceService.getSchoolSummary("year", null, null, SESSION)).thenReturn(List.of());

        Map<String, String> outcomes = service.sendAttendanceReminderEmailsWithOutcomes(List.of(STUDENT_ID), SESSION);

        assertThat(outcomes).containsEntry(STUDENT_ID, "failed");
        verifyNoInteractions(emailService);
    }

    @Test
    void sendAttendanceReminderEmailsWithOutcomes_studentWithNoEmailOnFile_reportsFailed() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        ClassAttendanceSummaryDTO summaryRow = new ClassAttendanceSummaryDTO(
                STUDENT_ID, "Jordan Test Student", "10", 10L, 3L, 7L, 30.0);
        when(attendanceService.getSchoolSummary("year", null, null, SESSION))
                .thenReturn(List.of(summaryRow));

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setName("Jordan Test Student");
        student.setEmail(null);
        student.setStatus(com.indraacademy.ias_management.entity.StudentStatus.ACTIVE);
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(student));

        Map<String, String> outcomes = service.sendAttendanceReminderEmailsWithOutcomes(List.of(STUDENT_ID), SESSION);

        // Specific outcome, not a bare "failed" — mirrors FeeReminderService's ReminderOutcome
        // vocabulary so a caller can tell "no email on file" apart from a real send failure.
        assertThat(outcomes).containsEntry(STUDENT_ID, "skipped_no_email");
        verifyNoInteractions(emailService);
    }

    // ─── Exit-status enforcement (defense in depth: not just candidate-list filtering) ───

    @Test
    void sendAttendanceReminderEmailsWithOutcomes_rejectsATransferredStudent_evenWithAQualifyingSummaryRow() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        ClassAttendanceSummaryDTO summaryRow = new ClassAttendanceSummaryDTO(
                STUDENT_ID, "Jordan Test Student", "10", 10L, 3L, 7L, 30.0);
        when(attendanceService.getSchoolSummary("year", null, null, SESSION))
                .thenReturn(List.of(summaryRow));

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setName("Jordan Test Student");
        student.setEmail("jordan.test@example.com");
        student.setStatus(com.indraacademy.ias_management.entity.StudentStatus.TRANSFERRED);
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(student));

        Map<String, String> outcomes = service.sendAttendanceReminderEmailsWithOutcomes(List.of(STUDENT_ID), SESSION);

        assertThat(outcomes).containsEntry(STUDENT_ID, "skipped_not_active");
        verifyNoInteractions(emailService);
    }

    /**
     * Covers the "AI workflow dispatch with exited student" and "stale/direct-API-supplied
     * exited studentId" cases together: studentIds arriving at this method come straight from
     * a dispatch request body (AiAttendanceWorkflowController / AiTeacherAttendanceWorkflowController),
     * not a freshly-recomputed candidate list — a withdrawn student mixed into an otherwise
     * valid batch must be skipped, not silently emailed, while the active student in the same
     * batch is unaffected.
     */
    @Test
    void sendAttendanceReminderEmailsWithOutcomes_withdrawnStudentInBatchIsSkipped_activeStudentInSameBatchStillSent() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        String activeId = "ACTIVE_STU";
        String withdrawnId = "WITHDRAWN_STU";

        ClassAttendanceSummaryDTO activeRow = new ClassAttendanceSummaryDTO(
                activeId, "Active Student", "10", 10L, 3L, 7L, 30.0);
        ClassAttendanceSummaryDTO withdrawnRow = new ClassAttendanceSummaryDTO(
                withdrawnId, "Withdrawn Student", "10", 10L, 3L, 7L, 30.0);
        when(attendanceService.getSchoolSummary("year", null, null, SESSION))
                .thenReturn(List.of(activeRow, withdrawnRow));

        Student active = new Student();
        active.setStudentId(activeId);
        active.setName("Active Student");
        active.setEmail("active@example.com");
        active.setStatus(com.indraacademy.ias_management.entity.StudentStatus.ACTIVE);
        when(studentRepository.findByStudentIdAndSchoolId(activeId, SCHOOL_ID)).thenReturn(Optional.of(active));

        Student withdrawn = new Student();
        withdrawn.setStudentId(withdrawnId);
        withdrawn.setName("Withdrawn Student");
        withdrawn.setEmail("withdrawn@example.com");
        withdrawn.setStatus(com.indraacademy.ias_management.entity.StudentStatus.WITHDRAWN);
        when(studentRepository.findByStudentIdAndSchoolId(withdrawnId, SCHOOL_ID)).thenReturn(Optional.of(withdrawn));

        when(emailService.sendHtmlEmailSync(anyString(), anyString(), anyString())).thenReturn(true);

        Map<String, String> outcomes = service.sendAttendanceReminderEmailsWithOutcomes(
                List.of(activeId, withdrawnId), SESSION);

        assertThat(outcomes).containsEntry(activeId, "sent");
        assertThat(outcomes).containsEntry(withdrawnId, "skipped_not_active");
        verify(emailService, times(1)).sendHtmlEmailSync(anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmailSync(eq("withdrawn@example.com"), anyString(), anyString());
    }
}
