package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.CopyResult;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.service.ClassTeacherResponsibilityCopyWorker.Evaluation;
import com.indraacademy.ias_management.service.ClassTeacherResponsibilityCopyWorker.Outcome;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassTeacherResponsibilityCopyServiceTest {

    @Mock private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Mock private TimetableSessionAccessService sessionAccess;
    @Mock private ClassTeacherResponsibilityCopyWorker worker;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private ClassTeacherResponsibilityCopyService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long SOURCE_ID = 10L;
    private static final Long TARGET_ID = 20L;

    @BeforeEach
    void setUp() {
        service = new ClassTeacherResponsibilityCopyService();
        ReflectionTestUtils.setField(service, "responsibilityRepository", responsibilityRepository);
        ReflectionTestUtils.setField(service, "sessionAccess", sessionAccess);
        ReflectionTestUtils.setField(service, "worker", worker);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(sessionAccess.requireOwnedSession(SCHOOL_ID, SOURCE_ID)).thenReturn(session(SOURCE_ID, false));
        lenient().when(sessionAccess.requireOwnedSession(SCHOOL_ID, TARGET_ID)).thenReturn(session(TARGET_ID, false));
    }

    private static AcademicSession session(Long id, boolean current) {
        AcademicSession s = new AcademicSession();
        s.setId(id);
        s.setCurrent(current);
        return s;
    }

    private static ClassTeacherResponsibility row(Long id) {
        ClassTeacherResponsibility r = new ClassTeacherResponsibility();
        r.setId(id);
        return r;
    }

    @Test
    void sourceEqualsTarget_rejected() {
        assertThatThrownBy(() -> service.copy(SOURCE_ID, SOURCE_ID, request)).isInstanceOf(IllegalArgumentException.class);
        verify(responsibilityRepository, never()).findByAcademicSessionIdAndSchoolId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void historicalTarget_rejected() {
        org.mockito.Mockito.doThrow(new IllegalStateException("Session has ended and is read-only."))
                .when(sessionAccess).requireWritable(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.copy(SOURCE_ID, TARGET_ID, request)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentTarget_doesNotRequireConfirmation_unlikeTimetableCopy() {
        // Deliberately different from TimetableSessionCopyService: responsibility rows have no
        // live effect until a separate, explicit activation apply(), so copying config INTO the
        // current session needs no extra confirmation flag.
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, TARGET_ID)).thenReturn(session(TARGET_ID, true));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SOURCE_ID, SCHOOL_ID)).thenReturn(List.of());

        CopyResult result = service.copy(SOURCE_ID, TARGET_ID, request);

        assertThat(result.scanned()).isZero();
    }

    @Test
    void aggregatesWorkerOutcomes() {
        ClassTeacherResponsibility r1 = row(1L), r2 = row(2L), r3 = row(3L);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SOURCE_ID, SCHOOL_ID)).thenReturn(List.of(r1, r2, r3));
        when(worker.attempt(SCHOOL_ID, r1, TARGET_ID)).thenReturn(Evaluation.copied(row(101L)));
        when(worker.attempt(SCHOOL_ID, r2, TARGET_ID)).thenReturn(Evaluation.alreadyCopied(row(102L)));
        when(worker.attempt(SCHOOL_ID, r3, TARGET_ID)).thenReturn(Evaluation.skipped(Outcome.SKIPPED_INELIGIBLE_TEACHER, "left"));

        CopyResult result = service.copy(SOURCE_ID, TARGET_ID, request);

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.copied()).isEqualTo(1);
        assertThat(result.alreadyCopied()).isEqualTo(1);
        assertThat(result.skippedIneligibleTeacher()).isEqualTo(1);
    }

    @Test
    void oneRowThrowing_doesNotPreventOthers() {
        ClassTeacherResponsibility bad = row(1L), good = row(2L);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SOURCE_ID, SCHOOL_ID)).thenReturn(List.of(bad, good));
        when(worker.attempt(SCHOOL_ID, bad, TARGET_ID)).thenThrow(new RuntimeException("boom"));
        when(worker.attempt(SCHOOL_ID, good, TARGET_ID)).thenReturn(Evaluation.copied(row(102L)));

        CopyResult result = service.copy(SOURCE_ID, TARGET_ID, request);

        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.copied()).isEqualTo(1);
    }

    @Test
    void neverTouchesTeacherLiveFieldsTimetableOrGrants() {
        // Naming the exact disallowed dependencies rather than substring-matching "Timetable"/
        // "Teacher" — TimetableSessionAccessService is a legitimate shared session-validation
        // helper (it touches no timetable or teacher data itself) and must not be flagged.
        assertThat(service.getClass().getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .extracting(Class::getSimpleName)
                .noneMatch(name -> name.equals("TeacherRepository") || name.equals("TimetableRepository")
                        || name.equals("TimetableService") || name.equals("TimetableSessionCopyService")
                        || name.equals("TimetableSessionCopyWorker") || name.equals("TeacherClassGrantRepository")
                        || name.equals("TeacherClassGrantService"));
    }
}
