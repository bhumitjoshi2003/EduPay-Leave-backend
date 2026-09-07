package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationApplyResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationPreviewResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationState;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ClassTeacherActivation;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.ClassTeacherActivationRepository;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The security-critical piece of Phase F4/F4.1: proves the preview/apply diff never grants or
 * clears scope based on anything other than the CURRENT session's configuration and each
 * candidate teacher's live ACTIVE status; that an ineligible configured row for one slot can
 * never clear a DIFFERENT, unrelated teacher's live assignment to that same slot (F4 bug fix);
 * and (F4.1) that {@code inSync} (a coincidence) and {@code activationState} (provenance) are
 * genuinely independent claims that can disagree.
 */
@ExtendWith(MockitoExtension.class)
class ClassTeacherActivationServiceTest {

    @Mock private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Mock private ClassTeacherActivationRepository activationRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private TimetableSessionAccessService sessionAccess;
    @Mock private AcademicSessionService academicSessionService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private ClassTeacherActivationService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long OTHER_SESSION_ID = 20L;
    private static final Long CLASS_A_ID = 100L;
    private static final Long CLASS_B_ID = 101L;
    private static final Long CLASS_C_ID = 102L;

    @BeforeEach
    void setUp() {
        service = new ClassTeacherActivationService();
        ReflectionTestUtils.setField(service, "responsibilityRepository", responsibilityRepository);
        ReflectionTestUtils.setField(service, "activationRepository", activationRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "sessionAccess", sessionAccess);
        ReflectionTestUtils.setField(service, "academicSessionService", academicSessionService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        AcademicSession current = session(SESSION_ID, true);
        lenient().when(academicSessionService.getCurrentSessionEntity()).thenReturn(current);
        lenient().when(sessionAccess.lockOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(current);

        lenient().when(schoolClassRepository.findByIdAndSchoolId(CLASS_A_ID, SCHOOL_ID)).thenReturn(Optional.of(schoolClass(CLASS_A_ID, "A")));
        lenient().when(schoolClassRepository.findByIdAndSchoolId(CLASS_B_ID, SCHOOL_ID)).thenReturn(Optional.of(schoolClass(CLASS_B_ID, "B")));
        lenient().when(schoolClassRepository.findByIdAndSchoolId(CLASS_C_ID, SCHOOL_ID)).thenReturn(Optional.of(schoolClass(CLASS_C_ID, "C")));

        lenient().when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of());
        lenient().when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId(any(), any())).thenReturn(List.of());
        lenient().when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of());
        lenient().when(teacherRepository.save(any(Teacher.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(activationRepository.save(any(ClassTeacherActivation.class))).thenAnswer(inv -> inv.getArgument(0));
        // Mockito's default answer already returns Optional.empty() for unstubbed Optional
        // methods, so "no activation record exists yet" needs no explicit stub.
    }

    private static AcademicSession session(Long id, boolean current) {
        AcademicSession s = new AcademicSession();
        s.setId(id);
        s.setCurrent(current);
        return s;
    }

    private static SchoolClass schoolClass(Long id, String name) {
        SchoolClass c = new SchoolClass();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static Teacher teacher(String id, TeacherStatus status, String liveClass, Long liveSection) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setName("Name-" + id);
        t.setStatus(status);
        t.setClassTeacher(liveClass);
        t.setClassTeacherSectionId(liveSection);
        return t;
    }

    private static ClassTeacherResponsibility configured(Long classId, Long sectionId, String teacherId) {
        ClassTeacherResponsibility r = new ClassTeacherResponsibility();
        r.setId(1L);
        r.setSchoolId(SCHOOL_ID);
        r.setAcademicSessionId(SESSION_ID);
        r.setClassId(classId);
        r.setSectionId(sectionId);
        r.setTeacherId(teacherId);
        return r;
    }

    private void stubTeacher(Teacher t) {
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId(t.getTeacherId(), SCHOOL_ID)).thenReturn(Optional.of(t));
    }

    /** Calls apply() and returns the persisted provenance record it wrote, so a follow-up test
     *  can feed it back into {@code activationRepository}'s stub to simulate "this was already
     *  applied earlier" without hand-computing the fingerprint. */
    private ClassTeacherActivation applyAndCaptureProvenance() {
        service.apply(request);
        ArgumentCaptor<ClassTeacherActivation> captor = ArgumentCaptor.forClass(ClassTeacherActivation.class);
        verify(activationRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ── preview: inSync vs activationState are independent claims (F4.1) ───────────────────

    @Test
    void preview_coincidentallyInSyncButNeverExplicitlyApplied_reportsNeverApplied() {
        // Exactly the F4.1 scenario: live already equals configured (e.g. carried over from a
        // prior session's arrangement, or hand-entered), but no activation record exists at all.
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, "A", null);
        stubTeacher(t1);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1));
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("A", SCHOOL_ID)).thenReturn(List.of(t1));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.inSync()).isTrue();
        assertThat(preview.activationState()).isEqualTo(ActivationState.NEVER_APPLIED);
        assertThat(preview.lastAppliedAt()).isNull();
        assertThat(preview.lastAppliedBy()).isNull();
    }

    @Test
    void apply_succeeds_becomesAppliedInSync_andPersistsProvenanceInSameCall() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, null, null);
        stubTeacher(t1);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));

        ActivationApplyResult result = service.apply(request);

        assertThat(result.activationState()).isEqualTo(ActivationState.APPLIED_IN_SYNC);
        assertThat(result.lastAppliedBy()).isEqualTo("admin");
        assertThat(result.lastAppliedAt()).isNotNull();

        ArgumentCaptor<ClassTeacherActivation> captor = ArgumentCaptor.forClass(ClassTeacherActivation.class);
        verify(activationRepository).save(captor.capture());
        assertThat(captor.getValue().getSchoolId()).isEqualTo(SCHOOL_ID);
        assertThat(captor.getValue().getAcademicSessionId()).isEqualTo(SESSION_ID);
        assertThat(captor.getValue().getConfigurationFingerprint()).isNotBlank();
    }

    @Test
    void preview_afterApply_thenResponsibilityChanged_reportsDrifted() {
        ClassTeacherActivation record = applyAndCaptureProvenance();
        when(activationRepository.findBySchoolIdAndAcademicSessionId(SCHOOL_ID, SESSION_ID)).thenReturn(Optional.of(record));

        // Live now reflects the applied state (T1 -> A) ...
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, "A", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1));
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("A", SCHOOL_ID)).thenReturn(List.of(t1));
        // ... but an admin has since reassigned class A to a different teacher in the responsibility table.
        Teacher t2 = teacher("T2", TeacherStatus.ACTIVE, null, null);
        when(teacherRepository.findByTeacherIdAndSchoolId("T2", SCHOOL_ID)).thenReturn(Optional.of(t2));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T2")));

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.activationState()).isEqualTo(ActivationState.APPLIED_BUT_DRIFTED);
        assertThat(preview.inSync()).isFalse(); // live (T1) no longer matches configured (T2)
    }

    @Test
    void preview_afterApply_thenDirectLiveEdit_reportsDrifted() {
        // Configuration itself never changes here — only the live projection is edited directly
        // (e.g. via the existing teacher-profile endpoint), bypassing apply() entirely.
        Teacher t1Initial = teacher("T1", TeacherStatus.ACTIVE, null, null);
        stubTeacher(t1Initial);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));
        ClassTeacherActivation record = applyAndCaptureProvenance();
        when(activationRepository.findBySchoolIdAndAcademicSessionId(SCHOOL_ID, SESSION_ID)).thenReturn(Optional.of(record));

        // Someone directly edits T1's live class-teacher assignment to class B instead of A —
        // configuration (still T1 -> A) is completely unchanged.
        Teacher t1Edited = teacher("T1", TeacherStatus.ACTIVE, "B", null);
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(t1Edited));
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1Edited));
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("A", SCHOOL_ID)).thenReturn(List.of());

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.activationState()).isEqualTo(ActivationState.APPLIED_BUT_DRIFTED);
    }

    @Test
    void preview_afterApply_thenTeacherExit_reflectedAsDrifted() {
        Teacher t1Initial = teacher("T1", TeacherStatus.ACTIVE, null, null);
        stubTeacher(t1Initial);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));
        ClassTeacherActivation record = applyAndCaptureProvenance();
        when(activationRepository.findBySchoolIdAndAcademicSessionId(SCHOOL_ID, SESSION_ID)).thenReturn(Optional.of(record));

        // T1 exits — TeacherService.exitTeacher clears both live fields (F4.1 fix) and flips status.
        Teacher t1Exited = teacher("T1", TeacherStatus.LEFT, null, null);
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(t1Exited));
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of()); // no longer live anywhere

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.activationState()).isEqualTo(ActivationState.APPLIED_BUT_DRIFTED);
        assertThat(preview.ineligibleTeacher()).isEqualTo(1); // configured row now names a LEFT teacher
    }

    @Test
    void preview_differentCurrentSession_doesNotInheritPreviousSessionsProvenance() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, null, null);
        stubTeacher(t1);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));
        applyAndCaptureProvenance(); // SESSION_ID is now APPLIED_IN_SYNC

        // The school switches its current session to a different one entirely.
        AcademicSession otherSession = session(OTHER_SESSION_ID, true);
        when(academicSessionService.getCurrentSessionEntity()).thenReturn(otherSession);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(OTHER_SESSION_ID, SCHOOL_ID)).thenReturn(List.of());
        // No stub for activationRepository.findBySchoolIdAndAcademicSessionId(SCHOOL_ID, OTHER_SESSION_ID)
        // — Mockito's default Optional.empty() is exactly correct: it has never been applied.

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.academicSessionId()).isEqualTo(OTHER_SESSION_ID);
        assertThat(preview.activationState()).isEqualTo(ActivationState.NEVER_APPLIED);
    }

    @Test
    void apply_repeatedIdempotentApply_remainsAppliedInSync() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, "A", null); // already correctly live
        stubTeacher(t1);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1));
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("A", SCHOOL_ID)).thenReturn(List.of(t1));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));

        ActivationApplyResult first = service.apply(request);
        ActivationApplyResult second = service.apply(request);

        assertThat(first.activationState()).isEqualTo(ActivationState.APPLIED_IN_SYNC);
        assertThat(second.activationState()).isEqualTo(ActivationState.APPLIED_IN_SYNC);
        verify(teacherRepository, never()).save(any()); // already correct both times — no writes
        verify(activationRepository, atLeastOnce()).save(any()); // provenance still (re-)recorded
    }

    @Test
    void apply_failureBeforeWritesComplete_neverRecordsProvenance() {
        // Simulate an unexpected mid-activation failure: the teacher named in a valid configured
        // row has vanished by write time (the same guard apply() already throws on).
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, null, null);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));
        // First lookup (inside computeDiff) succeeds so the row is judged valid+eligible ...
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(t1))   // computeDiff's lookup
                .thenReturn(Optional.empty()); // apply()'s write-loop re-fetch — "disappeared"

        assertThatThrownBy(() -> service.apply(request)).isInstanceOf(IllegalStateException.class);

        verify(activationRepository, never()).save(any());
    }

    // ── preview: unchanged from F4 ──────────────────────────────────────────────────────────

    @Test
    void preview_noCurrentSession_throws() {
        when(academicSessionService.getCurrentSessionEntity()).thenThrow(new IllegalStateException("No current academic session found."));

        assertThatThrownBy(() -> service.preview()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preview_becomingLive_noPriorHolder() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, null, null);
        stubTeacher(t1);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.becomingLive()).isEqualTo(1);
        assertThat(preview.changing()).isZero();
        assertThat(preview.clearing()).isZero();
        assertThat(preview.inSync()).isFalse();
        assertThat(preview.hasIssues()).isFalse();
    }

    @Test
    void preview_unchanged_liveAlreadyMatchesConfigured() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, "A", null);
        stubTeacher(t1);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1));
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("A", SCHOOL_ID)).thenReturn(List.of(t1));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.unchanged()).isEqualTo(1);
        assertThat(preview.becomingLive()).isZero();
        assertThat(preview.clearing()).isZero();
        assertThat(preview.inSync()).isTrue();
    }

    @Test
    void preview_changing_differentLiveHolder() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, null, null);
        Teacher t2 = teacher("T2", TeacherStatus.ACTIVE, "A", null); // currently live for class A
        stubTeacher(t1);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t2));
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("A", SCHOOL_ID)).thenReturn(List.of(t2));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1"))); // now configured for T1

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.changing()).isEqualTo(1);
        assertThat(preview.clearing()).isEqualTo(1); // T2 loses class A
        assertThat(preview.details()).anySatisfy(d -> {
            if ("CHANGING".equals(d.outcome())) {
                assertThat(d.configuredTeacherId()).isEqualTo("T1");
                assertThat(d.priorLiveTeacherId()).isEqualTo("T2");
            }
        });
    }

    @Test
    void preview_clearing_liveHolderWithNoConfiguredRowAtAll() {
        Teacher t3 = teacher("T3", TeacherStatus.ACTIVE, "C", null); // stale — class C has no responsibility row this session
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t3));

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.clearing()).isEqualTo(1);
        assertThat(preview.details()).anySatisfy(d -> {
            assertThat(d.outcome()).isEqualTo("TO_BE_CLEARED");
            assertThat(d.priorLiveTeacherId()).isEqualTo("T3");
        });
    }

    @Test
    void preview_ineligibleTeacher_doesNotClearUnrelatedCurrentHolderOfSameSlot() {
        Teacher tLeft = teacher("T-LEFT", TeacherStatus.LEFT, null, null);
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, "A", null);
        stubTeacher(tLeft);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T-LEFT")));

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.ineligibleTeacher()).isEqualTo(1);
        assertThat(preview.clearing()).isZero();
        assertThat(preview.hasIssues()).isTrue();
        assertThat(preview.details()).noneMatch(d -> "TO_BE_CLEARED".equals(d.outcome()));
    }

    @Test
    void preview_invalidClass_deletedClassReported() {
        when(schoolClassRepository.findByIdAndSchoolId(999L, SCHOOL_ID)).thenReturn(Optional.empty());
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(999L, null, "T1")));

        ActivationPreviewResult preview = service.preview();

        assertThat(preview.invalidClassOrSection()).isEqualTo(1);
        assertThat(preview.hasIssues()).isTrue();
    }

    // ── apply: unchanged from F4 ─────────────────────────────────────────────────────────────

    @Test
    void apply_noCurrentSession_throws() {
        when(academicSessionService.getCurrentSessionEntity()).thenThrow(new IllegalStateException("No current academic session found."));

        assertThatThrownBy(() -> service.apply(request)).isInstanceOf(IllegalStateException.class);
        verify(teacherRepository, never()).save(any());
    }

    @Test
    void apply_sessionChangedDuringActivation_rejected() {
        AcademicSession noLongerCurrent = session(SESSION_ID, false);
        when(sessionAccess.lockOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(noLongerCurrent);

        assertThatThrownBy(() -> service.apply(request)).isInstanceOf(IllegalStateException.class);
        verify(teacherRepository, never()).save(any());
        verify(activationRepository, never()).save(any());
    }

    @Test
    void apply_grantsBecomingLive() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, null, null);
        stubTeacher(t1);
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));

        ActivationApplyResult result = service.apply(request);

        assertThat(result.applied()).isEqualTo(1);
        ArgumentCaptor<Teacher> captor = ArgumentCaptor.forClass(Teacher.class);
        verify(teacherRepository).save(captor.capture());
        assertThat(captor.getValue().getClassTeacher()).isEqualTo("A");
        assertThat(captor.getValue().getClassTeacherSectionId()).isNull();
    }

    @Test
    void apply_clearsStaleLiveAssignmentNotBackedByCurrentConfiguration() {
        Teacher stale = teacher("T3", TeacherStatus.ACTIVE, "C", null); // no row at all for class C this session
        stubTeacher(stale);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(stale));

        ActivationApplyResult result = service.apply(request);

        assertThat(result.cleared()).isEqualTo(1);
        ArgumentCaptor<Teacher> captor = ArgumentCaptor.forClass(Teacher.class);
        verify(teacherRepository).save(captor.capture());
        assertThat(captor.getValue().getClassTeacher()).isNull();
        assertThat(captor.getValue().getClassTeacherSectionId()).isNull();
    }

    @Test
    void apply_alreadyCorrectAssignment_notRewritten() {
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, "A", null);
        stubTeacher(t1);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1));
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("A", SCHOOL_ID)).thenReturn(List.of(t1));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T1")));

        ActivationApplyResult result = service.apply(request);

        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.applied()).isZero();
        assertThat(result.cleared()).isZero();
        verify(teacherRepository, never()).save(any());
    }

    @Test
    void apply_ineligibleRow_leavesUnrelatedCurrentHolderUntouched() {
        Teacher tLeft = teacher("T-LEFT", TeacherStatus.LEFT, null, null);
        Teacher t1 = teacher("T1", TeacherStatus.ACTIVE, "A", null); // unrelated, currently correct-looking holder
        stubTeacher(tLeft);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1));
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of(configured(CLASS_A_ID, null, "T-LEFT")));

        ActivationApplyResult result = service.apply(request);

        assertThat(result.ineligibleTeacher()).isEqualTo(1);
        assertThat(result.cleared()).isZero();
        assertThat(result.applied()).isZero();
        verify(teacherRepository, never()).save(any()); // T1 is never touched at all
    }
}
