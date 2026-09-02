package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TimetableService's manual create/update/delete flow — wiring, tenant
 * isolation, and delegation to TimetableValidationService. The validation rule matrix itself
 * is covered exhaustively in TimetableValidationServiceTest; here we mock that service and
 * only assert TimetableService calls it correctly and respects its verdict.
 */
@ExtendWith(MockitoExtension.class)
class TimetableServiceTest {

    @Mock private TimetableRepository timetableRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private TimetableValidationService timetableValidationService;
    @Mock private TeacherClassScopeService teacherClassScopeService;
    @Mock private com.indraacademy.ias_management.repository.TeacherClassGrantRepository teacherClassGrantRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private TimetableService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new TimetableService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "timetableValidationService", timetableValidationService);
        ReflectionTestUtils.setField(service, "teacherClassScopeService", teacherClassScopeService);
        ReflectionTestUtils.setField(service, "teacherClassGrantRepository", teacherClassGrantRepository);
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
    }

    private TimetableEntry entry(String subject, String teacherId, String group) {
        TimetableEntry e = new TimetableEntry();
        e.setClassName("10");
        e.setDay(Day.MONDAY);
        e.setPeriodNumber(3);
        e.setStartTime("09:00");
        e.setEndTime("09:40");
        e.setSubjectName(subject);
        e.setTeacherId(teacherId);
        e.setSimultaneousGroup(group);
        return e;
    }

    @Test
    void create_validatesThenSaves() {
        TimetableEntry entry = entry("Mathematics", "T1", null);

        TimetableEntry saved = service.create(entry, "ADMIN", "admin1", request);

        verify(timetableValidationService).validate(entry, SCHOOL_ID, null);
        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);
        verify(timetableRepository).save(entry);
    }

    @Test
    void create_propagatesValidationFailure_neverSaves() {
        TimetableEntry entry = entry("Mathematics", "T1", null);
        doThrow(new DataIntegrityViolationException("Period already assigned"))
                .when(timetableValidationService).validate(entry, SCHOOL_ID, null);

        assertThatThrownBy(() -> service.create(entry, "ADMIN", "admin1", request))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(timetableRepository, never()).save(any());
    }

    // ── create — TEACHER self-service ("+ Add Period" from a teacher's own schedule) ──

    @Test
    void create_teacherRole_forcesOwnTeacherId_ignoringSpoofedValue() {
        TimetableEntry entry = entry("Mathematics", "T999", null); // spoofed teacherId
        entry.setSectionId(5L);
        TimetableEntry alreadyTeaches = entry("Physics", "T1", null);
        alreadyTeaches.setSectionId(5L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of(alreadyTeaches));

        service.create(entry, "TEACHER", "T1", request);

        assertThat(entry.getTeacherId()).isEqualTo("T1");
        verify(timetableValidationService).validate(entry, SCHOOL_ID, null);
    }

    @Test
    void create_teacherRole_alreadyTeachesClassAndSection_allowed() {
        TimetableEntry entry = entry("Mathematics", "T1", null);
        entry.setSectionId(5L);
        TimetableEntry alreadyTeaches = entry("Physics", "T1", null);
        alreadyTeaches.setSectionId(5L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of(alreadyTeaches));

        TimetableEntry saved = service.create(entry, "TEACHER", "T1", request);

        assertThat(saved).isNotNull();
        verify(teacherClassScopeService, never()).resolveOwnScope(any(), any());
    }

    @Test
    void create_teacherRole_isClassTeacherOfExactSection_allowed() {
        TimetableEntry entry = entry("Mathematics", "T1", null);
        entry.setClassName("11");
        entry.setSectionId(5L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope("11", 5L, false));

        TimetableEntry saved = service.create(entry, "TEACHER", "T1", request);

        assertThat(saved).isNotNull();
        verify(timetableValidationService).validate(entry, SCHOOL_ID, null);
    }

    @Test
    void create_teacherRole_notConnectedToClass_rejected() {
        TimetableEntry entry = entry("Mathematics", "T1", null);
        entry.setClassName("11");
        entry.setSectionId(5L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));

        assertThatThrownBy(() -> service.create(entry, "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableValidationService, never()).validate(any(), anyLong(), any());
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void create_teacherRole_legacyAmbiguousClassTeacherAssignment_stillRejected() {
        // Same class name, but the class-teacher assignment is ambiguous (a sectioned class with
        // no section on file) — must not be treated as broadened write access.
        TimetableEntry entry = entry("Mathematics", "T1", null);
        entry.setClassName("11");
        entry.setSectionId(5L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope("11", null, true));

        assertThatThrownBy(() -> service.create(entry, "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
    }

    @Test
    void create_teacherRole_hasAdminGrantForClassAndSection_allowed() {
        // No existing periods there, not the class-teacher — but an admin explicitly granted
        // access to exactly this class+section (e.g. the teacher's genuine first period there).
        TimetableEntry entry = entry("Mathematics", "T1", null);
        entry.setClassName("12");
        entry.setSectionId(9L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));
        when(teacherClassGrantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "12", 9L, SCHOOL_ID))
                .thenReturn(true);

        TimetableEntry saved = service.create(entry, "TEACHER", "T1", request);

        assertThat(saved).isNotNull();
        verify(timetableValidationService).validate(entry, SCHOOL_ID, null);
    }

    @Test
    void create_teacherRole_grantExistsForDifferentSection_stillRejected() {
        TimetableEntry entry = entry("Mathematics", "T1", null);
        entry.setClassName("12");
        entry.setSectionId(9L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));
        // Grant exists, but for a different section (10L, not the requested 9L)
        when(teacherClassGrantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "12", 9L, SCHOOL_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(entry, "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
    }

    @Test
    void update_mergesIncomingFieldsIncludingSimultaneousGroup_andValidatesExcludingSelf() {
        TimetableEntry existing = entry("Hindi", "T1", null);
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        TimetableEntry incoming = entry("Mathematics", "T2", "MATH_BIO");

        TimetableEntry saved = service.update(5L, incoming, request);

        ArgumentCaptor<TimetableEntry> captor = ArgumentCaptor.forClass(TimetableEntry.class);
        verify(timetableValidationService).validate(captor.capture(), eq(SCHOOL_ID), eq(5L));
        assertThat(captor.getValue().getSubjectName()).isEqualTo("Mathematics");
        assertThat(captor.getValue().getSimultaneousGroup()).isEqualTo("MATH_BIO");
        assertThat(saved.getSubjectName()).isEqualTo("Mathematics");
        assertThat(saved.getSimultaneousGroup()).isEqualTo("MATH_BIO");
    }

    @Test
    void update_entryFromAnotherSchool_notFound() {
        TimetableEntry existing = entry("Hindi", "T1", null);
        existing.setId(5L);
        existing.setSchoolId(999L); // different school
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(5L, entry("Mathematics", "T2", null), request))
                .isInstanceOf(NoSuchElementException.class);

        verify(timetableValidationService, never()).validate(any(), anyLong(), any());
    }

    @Test
    void delete_entryFromAnotherSchool_notFound() {
        TimetableEntry existing = entry("Hindi", "T1", null);
        existing.setId(5L);
        existing.setSchoolId(999L);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(5L, request))
                .isInstanceOf(NoSuchElementException.class);

        verify(timetableRepository, never()).deleteById(any());
    }

    @Test
    void delete_ownSchoolEntry_succeeds() {
        TimetableEntry existing = entry("Hindi", "T1", null);
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.delete(5L, request);

        verify(timetableRepository, times(1)).deleteById(5L);
    }

    // ── addSimultaneous ("+ Simultaneous" — automatic tag, no admin input) ────────────

    @Test
    void addSimultaneous_existingHasNoGroup_generatesAndSharesTagWithNewEntry() {
        TimetableEntry existing = entry("Mathematics", "T1", null);
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setClassId(42L);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        TimetableEntry saved = service.addSimultaneous(5L, "Biology", "T2", "ADMIN", "admin1", request);

        assertThat(existing.getSimultaneousGroup()).isNotBlank();
        assertThat(saved.getSimultaneousGroup()).isEqualTo(existing.getSimultaneousGroup());
        assertThat(saved.getSubjectName()).isEqualTo("Biology");
        assertThat(saved.getTeacherId()).isEqualTo("T2");
        assertThat(saved.getClassName()).isEqualTo(existing.getClassName());
        assertThat(saved.getClassId()).isEqualTo(42L);
        assertThat(saved.getDay()).isEqualTo(existing.getDay());
        assertThat(saved.getPeriodNumber()).isEqualTo(existing.getPeriodNumber());
        assertThat(saved.getStartTime()).isEqualTo(existing.getStartTime());
        assertThat(saved.getEndTime()).isEqualTo(existing.getEndTime());
        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);

        // existing was re-saved with its new tag, and the candidate was saved with the same tag
        verify(timetableRepository, times(2)).save(any(TimetableEntry.class));
    }

    @Test
    void addSimultaneous_existingAlreadyHasGroup_reusesTag_doesNotResaveExisting() {
        TimetableEntry existing = entry("Mathematics", "T1", "MATH_BIO");
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        TimetableEntry saved = service.addSimultaneous(5L, "Biology", "T2", "ADMIN", "admin1", request);

        assertThat(saved.getSimultaneousGroup()).isEqualTo("MATH_BIO");
        // existing already had a tag — only the new candidate should be persisted
        verify(timetableRepository, times(1)).save(any(TimetableEntry.class));
    }

    @Test
    void addSimultaneous_validatesCandidateAgainstSharedValidationService() {
        TimetableEntry existing = entry("Mathematics", "T1", "MATH_BIO");
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.addSimultaneous(5L, "Biology", "T2", "ADMIN", "admin1", request);

        ArgumentCaptor<TimetableEntry> captor = ArgumentCaptor.forClass(TimetableEntry.class);
        verify(timetableValidationService).validate(captor.capture(), eq(SCHOOL_ID), org.mockito.ArgumentMatchers.isNull());
        assertThat(captor.getValue().getSimultaneousGroup()).isEqualTo("MATH_BIO");
        assertThat(captor.getValue().getSubjectName()).isEqualTo("Biology");
    }

    @Test
    void addSimultaneous_validationFails_candidateNeverSaved() {
        TimetableEntry existing = entry("Mathematics", "T1", "MATH_BIO");
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));
        doThrow(new DataIntegrityViolationException("Teacher already has an overlapping period"))
                .when(timetableValidationService).validate(any(TimetableEntry.class), eq(SCHOOL_ID), org.mockito.ArgumentMatchers.isNull());

        assertThatThrownBy(() -> service.addSimultaneous(5L, "Biology", "T2", "ADMIN", "admin1", request))
                .isInstanceOf(DataIntegrityViolationException.class);

        // existing already had a tag (no pre-save needed), and validation rejected the
        // candidate before it could be persisted
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void addSimultaneous_entryFromAnotherSchool_notFound() {
        TimetableEntry existing = entry("Mathematics", "T1", null);
        existing.setId(5L);
        existing.setSchoolId(999L);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.addSimultaneous(5L, "Biology", "T2", "ADMIN", "admin1", request))
                .isInstanceOf(NoSuchElementException.class);

        verify(timetableValidationService, never()).validate(any(), anyLong(), any());
    }

    // ── addSimultaneous — TEACHER self-service ────────────────────────────────────────

    @Test
    void addSimultaneous_teacherRole_forcesOwnTeacherId_ignoringRequestedTeacherId() {
        TimetableEntry existing = entry("Mathematics", "T1", "MATH_BIO");
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setSectionId(5L);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));
        TimetableEntry alreadyTeaches = entry("Physics", "T1", null);
        alreadyTeaches.setSectionId(5L);
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of(alreadyTeaches));

        TimetableEntry saved = service.addSimultaneous(5L, "Biology", "T999" /* spoofed */, "TEACHER", "T1", request);

        assertThat(saved.getTeacherId()).isEqualTo("T1");
    }

    @Test
    void addSimultaneous_teacherRole_notConnectedToExistingEntrysClass_rejected() {
        TimetableEntry existing = entry("Mathematics", "T2", "MATH_BIO");
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setSectionId(5L);
        when(timetableRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(timetableRepository.findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc("T1", SCHOOL_ID))
                .thenReturn(java.util.List.of());
        when(teacherClassScopeService.resolveOwnScope("T1", SCHOOL_ID))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));

        assertThatThrownBy(() -> service.addSimultaneous(5L, "Biology", "T1", "TEACHER", "T1", request))
                .isInstanceOf(SecurityException.class);

        verify(timetableRepository, never()).save(any());
    }
}
