package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.TimetableDtos.TimetableEntryRequest;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TimetableService's manual create/update/delete flow — wiring, tenant/session
 * isolation, canonical class/section resolution, and ownership authorization. The timetable is a
 * permissive schedule record: this service never rejects a write because another row already
 * occupies the same slot (same or different subject, same or different teacher) — reviewing and
 * correcting such rows is the ADMIN's responsibility, not this service's. Session
 * ownership/writability is mocked via TimetableSessionAccessService directly.
 */
@ExtendWith(MockitoExtension.class)
class TimetableServiceTest {

    @Mock private TimetableRepository timetableRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private TeacherClassScopeService teacherClassScopeService;
    @Mock private com.indraacademy.ias_management.repository.TeacherClassGrantRepository teacherClassGrantRepository;
    @Mock private TimetableSessionAccessService sessionAccess;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private TimetableService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CLASS_10_ID = 100L; // no sections
    private static final Long CLASS_11_ID = 111L; // has sections
    private static final Long CLASS_12_ID = 112L; // has sections
    private static final Long SECTION_5_ID = 5L;
    private static final Long SECTION_9_ID = 9L;

    @BeforeEach
    void setUp() {
        service = new TimetableService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "teacherClassScopeService", teacherClassScopeService);
        ReflectionTestUtils.setField(service, "teacherClassGrantRepository", teacherClassGrantRepository);
        ReflectionTestUtils.setField(service, "sessionAccess", sessionAccess);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(timetableRepository.save(any(TimetableEntry.class))).thenAnswer(inv -> {
            TimetableEntry e = inv.getArgument(0);
            if (e.getId() == null) e.setId(100L);
            return e;
        });

        AcademicSession session = session(SESSION_ID);
        lenient().when(sessionAccess.requireWritableOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);
        lenient().when(sessionAccess.requireCurrentSessionForTeacherWrite(SCHOOL_ID)).thenReturn(session);
        lenient().when(sessionAccess.currentSessionOrNull(SCHOOL_ID)).thenReturn(session);

        stubClass(CLASS_10_ID, "10", false);
        stubClass(CLASS_11_ID, "11", true);
        stubClass(CLASS_12_ID, "12", true);
        lenient().when(sectionRepository.findByIdAndSchoolId(SECTION_5_ID, SCHOOL_ID))
                .thenReturn(Optional.of(section(SECTION_5_ID, CLASS_11_ID, "A")));
        lenient().when(sectionRepository.findByIdAndSchoolId(SECTION_9_ID, SCHOOL_ID))
                .thenReturn(Optional.of(section(SECTION_9_ID, CLASS_12_ID, "B")));
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId(any(), eq(SCHOOL_ID)))
                .thenAnswer(inv -> Optional.of(teacher(inv.getArgument(0), "Teacher Name")));
    }

    private static AcademicSession session(Long id) {
        AcademicSession s = new AcademicSession();
        s.setId(id);
        s.setLabel("2026-2027");
        s.setCurrent(true);
        return s;
    }

    private static SchoolClass schoolClass(Long id, String name) {
        SchoolClass sc = new SchoolClass();
        sc.setId(id);
        sc.setName(name);
        sc.setActive(true);
        return sc;
    }

    private static Section section(Long id, Long classId, String name) {
        Section s = new Section();
        s.setId(id);
        s.setClassId(classId);
        s.setName(name);
        s.setActive(true);
        return s;
    }

    private static Teacher teacher(String id, String name) {
        Teacher t = new Teacher();
        ReflectionTestUtils.setField(t, "teacherId", id);
        ReflectionTestUtils.setField(t, "name", name);
        return t;
    }

    private void stubClass(Long classId, String name, boolean hasSections) {
        lenient().when(schoolClassRepository.findByIdAndSchoolId(classId, SCHOOL_ID))
                .thenReturn(Optional.of(schoolClass(classId, name)));
        lenient().when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, classId, true))
                .thenReturn(hasSections ? List.of(new Section()) : List.of());
    }

    private TimetableEntryRequest req(Long classId, Long sectionId, String subject, String teacherId) {
        return new TimetableEntryRequest(SESSION_ID, classId, sectionId, Day.MONDAY, 3, "09:00", "09:40", subject, teacherId);
    }

    private TimetableEntry existing(String subject, String teacherId, Long classId, Long sectionId) {
        TimetableEntry e = new TimetableEntry();
        e.setId(5L);
        e.setSchoolId(SCHOOL_ID);
        e.setAcademicSessionId(SESSION_ID);
        e.setClassId(classId);
        e.setClassName(classId.equals(CLASS_11_ID) ? "11" : classId.equals(CLASS_12_ID) ? "12" : "10");
        e.setSectionId(sectionId);
        e.setDay(Day.MONDAY);
        e.setPeriodNumber(3);
        e.setStartTime("09:00");
        e.setEndTime("09:40");
        e.setSubjectName(subject);
        e.setTeacherId(teacherId);
        return e;
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────

    @Test
    void getByClass_operationalRead_resolvesCurrentSessionAndScopesQuery() {
        TimetableEntry row = existing("Mathematics", "T1", CLASS_10_ID, null);
        when(timetableRepository.findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(
                SESSION_ID, "10", SCHOOL_ID)).thenReturn(List.of(row));

        List<TimetableEntry> result = service.getByClass("10", null);

        assertThat(result).containsExactly(row);
        verify(sessionAccess).currentSessionOrNull(SCHOOL_ID);
    }

    @Test
    void getByClass_noCurrentSession_returnsEmptyRatherThanFallingBackToLegacyRows() {
        when(sessionAccess.currentSessionOrNull(SCHOOL_ID)).thenReturn(null);

        List<TimetableEntry> result = service.getByClass("10", null);

        assertThat(result).isEmpty();
        verify(timetableRepository, never()).findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(any(), any(), any());
    }

    @Test
    void getByTeacher_operationalRead_scopedToCurrentSessionOnly() {
        service.getByTeacher("T1");

        verify(timetableRepository).findByAcademicSessionIdAndTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc(SESSION_ID, "T1", SCHOOL_ID);
    }

    @Test
    void getByClassForSession_adminExplicitRead_usesRequestedSessionNotCurrent() {
        Long historicalSessionId = 5L;
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, historicalSessionId)).thenReturn(session(historicalSessionId));
        TimetableEntry row = existing("Mathematics", "T1", CLASS_10_ID, null);
        when(timetableRepository.findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(
                historicalSessionId, "10", SCHOOL_ID)).thenReturn(List.of(row));

        List<TimetableEntry> result = service.getByClassForSession("10", null, historicalSessionId);

        assertThat(result).containsExactly(row);
        verify(sessionAccess, never()).currentSessionOrNull(any());
        verify(timetableRepository, never()).findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(
                eq(SESSION_ID), any(), any());
    }

    @Test
    void getByTeacherForSession_unownedSession_rejected() {
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, 999L))
                .thenThrow(new NoSuchElementException("Academic session not found: 999"));

        assertThatThrownBy(() -> service.getByTeacherForSession("T1", 999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── create ───────────────────────────────────────────────────────────────────────────────

    @Test
    void create_resolvesCanonicalClassAndSaves() {
        TimetableEntryRequest req = req(CLASS_10_ID, null, "Mathematics", "T1");

        TimetableEntry saved = service.create(req, "ADMIN", "admin1", request);

        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);
        assertThat(saved.getAcademicSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getClassId()).isEqualTo(CLASS_10_ID);
        assertThat(saved.getClassName()).isEqualTo("10");
        assertThat(saved.getTeacherName()).isEqualTo("Teacher Name");
        verify(timetableRepository).save(any(TimetableEntry.class));
    }

    @Test
    void create_neverTrustsClientClassName_derivesFromCanonicalId() {
        // The DTO carries no className field at all — this test documents that the saved
        // className always comes from the resolved SchoolClass, never from anything client-sent.
        TimetableEntry saved = service.create(req(CLASS_11_ID, SECTION_5_ID, "Physics", "T1"), "ADMIN", "admin1", request);

        assertThat(saved.getClassName()).isEqualTo("11");
        assertThat(saved.getSectionName()).isEqualTo("A");
    }

    @Test
    void create_classWithSectionsButNoneSupplied_rejected() {
        assertThatThrownBy(() -> service.create(req(CLASS_11_ID, null, "Physics", "T1"), "ADMIN", "admin1", request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void create_sectionlessClassWithSectionSupplied_rejected() {
        assertThatThrownBy(() -> service.create(req(CLASS_10_ID, SECTION_5_ID, "Physics", "T1"), "ADMIN", "admin1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_sectionBelongsToDifferentClass_rejected() {
        // SECTION_5_ID belongs to CLASS_11_ID, not CLASS_12_ID.
        assertThatThrownBy(() -> service.create(req(CLASS_12_ID, SECTION_5_ID, "Physics", "T1"), "ADMIN", "admin1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_unknownClassId_rejected() {
        when(schoolClassRepository.findByIdAndSchoolId(999L, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req(999L, null, "Physics", "T1"), "ADMIN", "admin1", request))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void create_adminTargetsHistoricalSession_rejected() {
        when(sessionAccess.requireWritableOwnedSession(SCHOOL_ID, SESSION_ID))
                .thenThrow(new IllegalStateException("Session 2020-2021 has ended and is read-only."));

        assertThatThrownBy(() -> service.create(req(CLASS_10_ID, null, "Physics", "T1"), "ADMIN", "admin1", request))
                .isInstanceOf(IllegalStateException.class);
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void create_neverChecksForAnExistingOccupantOfTheSameSlot() {
        // The NEW product rule: the timetable never rejects a row for colliding with another row
        // in the same school/session/class/section/day/period — same subject, different teacher,
        // or anything else. Reviewing/correcting mistakes is the ADMIN's job, not this service's.
        // Proven here by the absence of any query for existing occupants before saving.
        TimetableEntry saved = service.create(req(CLASS_10_ID, null, "Mathematics", "T2"), "ADMIN", "admin1", request);

        assertThat(saved).isNotNull();
        assertThat(saved.getTeacherId()).isEqualTo("T2");
        verify(timetableRepository).save(any(TimetableEntry.class));
        verify(timetableRepository, never()).findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(any(), any(), any());
    }

    // ── create — TEACHER self-service ("+ Add Period" from a teacher's own schedule) ──

    @Test
    void create_teacherRole_targetsCurrentSessionRegardlessOfRequest_andForcesOwnTeacherId() {
        TimetableEntry alreadyTeaches = existing("Physics", "T1", CLASS_11_ID, SECTION_5_ID);
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of(alreadyTeaches));

        TimetableEntry saved = service.create(req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T999" /* spoofed */),
                "TEACHER", "T1", request);

        assertThat(saved.getTeacherId()).isEqualTo("T1");
        assertThat(saved.getAcademicSessionId()).isEqualTo(SESSION_ID);
        verify(sessionAccess).requireCurrentSessionForTeacherWrite(SCHOOL_ID);
        verify(sessionAccess, never()).requireWritableOwnedSession(any(), any());
    }

    @Test
    void create_teacherRole_taughtSameClassSectionOnlyInADifferentSession_doesNotBootstrapCurrentAccess() {
        // A historical (or future) row naming this teacher for this exact class/section must not
        // by itself unlock a NEW write in the current session — "already teaches here" is scoped
        // to the current session only. With no current-session row and no class-teacher/grant
        // relationship either, the write must be denied.
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of()); // nothing for T1 in the CURRENT session
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));
        when(teacherClassGrantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "11", SECTION_5_ID, SCHOOL_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T1"), "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
        // The service never even asks about a non-current session for this check.
        verify(timetableRepository, never()).findByAcademicSessionIdAndTeacherIdAndSchoolId(eq(999L), any(), any());
    }

    @Test
    void create_teacherRole_alreadyTeachesClassAndSectionThisSession_allowed() {
        TimetableEntry alreadyTeaches = existing("Physics", "T1", CLASS_11_ID, SECTION_5_ID);
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of(alreadyTeaches));

        TimetableEntry saved = service.create(req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T1"), "TEACHER", "T1", request);

        assertThat(saved).isNotNull();
        verify(teacherClassScopeService, never()).resolveOwnScope(any(), any());
    }

    @Test
    void create_teacherRole_isClassTeacherOfExactSection_allowed() {
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope("11", SECTION_5_ID, false));

        TimetableEntry saved = service.create(req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T1"), "TEACHER", "T1", request);

        assertThat(saved).isNotNull();
    }

    @Test
    void create_teacherRole_notConnectedToClass_rejected() {
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));

        assertThatThrownBy(() -> service.create(req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T1"), "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
    }

    @Test
    void create_teacherRole_legacyAmbiguousClassTeacherAssignment_stillRejected() {
        // Same class name, but the class-teacher assignment is ambiguous (a sectioned class with
        // no section on file) — must not be treated as broadened write access.
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope("11", null, true));

        assertThatThrownBy(() -> service.create(req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T1"), "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
    }

    @Test
    void create_teacherRole_hasAdminGrantForClassAndSection_allowed() {
        // No existing periods there, not the class-teacher — but an admin explicitly granted
        // access to exactly this class+section (e.g. the teacher's genuine first period there).
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));
        when(teacherClassGrantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "12", SECTION_9_ID, SCHOOL_ID))
                .thenReturn(true);

        TimetableEntry saved = service.create(req(CLASS_12_ID, SECTION_9_ID, "Mathematics", "T1"), "TEACHER", "T1", request);

        assertThat(saved).isNotNull();
    }

    @Test
    void create_teacherRole_grantExistsForDifferentSection_stillRejected() {
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));
        when(teacherClassGrantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "12", SECTION_9_ID, SCHOOL_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(req(CLASS_12_ID, SECTION_9_ID, "Mathematics", "T1"), "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
    }

    // ── update ───────────────────────────────────────────────────────────────────────────────

    @Test
    void update_mergesIncomingFieldsAndSaves() {
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        TimetableEntry saved = service.update(5L, req(CLASS_10_ID, null, "Mathematics", "T2"), request);

        assertThat(saved.getSubjectName()).isEqualTo("Mathematics");
        assertThat(saved.getTeacherId()).isEqualTo("T2");
    }

    @Test
    void update_targetSlotAlreadyOccupiedByAnotherRow_stillSucceeds() {
        // The service performs no slot-occupancy check at all any more — updating into a slot
        // that another row (or several) already occupies is not even a query it makes, let alone
        // a rejection.
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        TimetableEntry saved = service.update(5L, req(CLASS_10_ID, null, "Mathematics", "T1"), request);

        assertThat(saved.getSubjectName()).isEqualTo("Mathematics");
        verify(timetableRepository).save(existing);
        verify(timetableRepository, never()).findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(any(), any(), any());
    }

    @Test
    void update_requestedSessionDoesNotMatchEntrysActualSession_rejectedClosed() {
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        existing.setAcademicSessionId(999L); // different session than the request will claim
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(5L, req(CLASS_10_ID, null, "Mathematics", "T2"), request))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("does not match");

        verify(timetableRepository, never()).save(any());
    }

    @Test
    void update_targetsHistoricalSession_rejected() {
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(sessionAccess.requireWritableOwnedSession(SCHOOL_ID, SESSION_ID))
                .thenThrow(new IllegalStateException("Session has ended and is read-only."));

        assertThatThrownBy(() -> service.update(5L, req(CLASS_10_ID, null, "Mathematics", "T2"), request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void update_entryFromAnotherSchool_notFound() {
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        existing.setSchoolId(999L); // different school
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(5L, req(CLASS_10_ID, null, "Mathematics", "T2"), request))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── delete ───────────────────────────────────────────────────────────────────────────────

    @Test
    void delete_entryFromAnotherSchool_notFound() {
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        existing.setSchoolId(999L);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(5L, SESSION_ID, request))
                .isInstanceOf(NoSuchElementException.class);

        verify(timetableRepository, never()).deleteById(any());
    }

    @Test
    void delete_requestedSessionMismatch_rejectedClosed() {
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(5L, 999L /* wrong session */, request))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(timetableRepository, never()).deleteById(any());
    }

    @Test
    void delete_ownSchoolAndSessionEntry_succeeds() {
        TimetableEntry existing = existing("Hindi", "T1", CLASS_10_ID, null);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.delete(5L, SESSION_ID, request);

        verify(timetableRepository, times(1)).deleteById(5L);
    }

    // ── TEACHER self-service update/delete — ownership only, never a class-authorization bypass ──

    @Test
    void update_teacherOwnEntry_allowed() {
        TimetableEntry existing = existing("Physics", "T1", CLASS_11_ID, SECTION_5_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of(existing));

        TimetableEntry saved = service.update(5L, req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T1"), "TEACHER", "T1", request);

        assertThat(saved.getSubjectName()).isEqualTo("Mathematics");
        assertThat(saved.getTeacherId()).isEqualTo("T1");
    }

    @Test
    void update_teacherAnotherTeachersEntry_rejected() {
        TimetableEntry existing = existing("Physics", "T1", CLASS_11_ID, SECTION_5_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(5L, req(CLASS_11_ID, SECTION_5_ID, "Mathematics", "T2"), "TEACHER", "T2", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
    }

    @Test
    void delete_teacherOwnEntry_allowed() {
        TimetableEntry existing = existing("Physics", "T1", CLASS_11_ID, SECTION_5_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of(existing));

        service.delete(5L, SESSION_ID, "TEACHER", "T1", request);

        verify(timetableRepository).deleteById(5L);
    }

    @Test
    void delete_teacherAnotherTeachersEntry_rejected() {
        TimetableEntry existing = existing("Physics", "T1", CLASS_11_ID, SECTION_5_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(5L, SESSION_ID, "TEACHER", "T2", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).deleteById(any());
    }
}
