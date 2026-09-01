package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in that date of birth is mandatory for a newly-created teacher — it is the
 * only source of the DOB-derived initial login password (see AuthController.registerUser
 * and TeacherBulkImportService), so a teacher created without one must never reach the
 * database.
 */
@ExtendWith(MockitoExtension.class)
class TeacherServiceTest {

    @Mock private TeacherRepository teacherRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private EntitlementService entitlementService;
    @Mock private IdGeneratorService idGeneratorService;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private HttpServletRequest request;

    private TeacherService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new TeacherService();
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "entitlementService", entitlementService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(service, "idGeneratorService", idGeneratorService);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(teacherRepository.save(any(Teacher.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Teacher newTeacher(String teacherId, LocalDate dob) {
        Teacher t = new Teacher();
        t.setTeacherId(teacherId);
        t.setName("Test Teacher");
        t.setDob(dob);
        t.setJoiningDate(LocalDate.of(2026, 4, 1));
        return t;
    }

    @Test
    void addTeacherWithValidDobSucceeds() {
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(teacherRepository.existsById("T1")).thenReturn(false);

        Teacher saved = service.addTeacher(newTeacher("T1", LocalDate.of(1985, 3, 20)), request);

        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);
        verify(teacherRepository).save(any(Teacher.class));
    }

    @Test
    void addTeacherWithoutDobIsRejected() {
        assertThatThrownBy(() -> service.addTeacher(newTeacher("T1", null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Date of birth is required");

        verify(teacherRepository, never()).save(any());
    }

    @Test
    void addTeacherWithoutJoiningDateIsRejected() {
        Teacher teacher = newTeacher("T1", LocalDate.of(1985, 3, 20));
        teacher.setJoiningDate(null);

        assertThatThrownBy(() -> service.addTeacher(teacher, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Joining date is required");

        verify(teacherRepository, never()).save(any());
    }

    @Test
    void duplicateTeacherIdWithinSchoolIsRejected() {
        Teacher existing = newTeacher("T1", LocalDate.of(1985, 3, 20));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.addTeacher(newTeacher("T1", LocalDate.of(1985, 3, 20)), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(teacherRepository, never()).save(any());
    }

    // ─── System-generated Employee ID ──────────────────────────────────────

    @Test
    void newTeacherWithNoIdSuppliedGetsAGeneratedId() {
        when(idGeneratorService.generateTeacherId()).thenReturn("emp_26010007");
        when(teacherRepository.findByTeacherIdAndSchoolId("emp_26010007", SCHOOL_ID)).thenReturn(Optional.empty());
        when(teacherRepository.existsById("emp_26010007")).thenReturn(false);

        Teacher saved = service.addTeacher(newTeacher(null, LocalDate.of(1985, 3, 20)), request);

        assertThat(saved.getTeacherId()).isEqualTo("emp_26010007");
        verify(idGeneratorService).generateTeacherId();
    }

    @Test
    void existingSuppliedTeacherIdIsNeverOverwritten_backwardCompatibility() {
        // The Indra Academy scenario this directly protects: an existing teacher's legacy
        // SWEEDU-era ID (or any other non-blank supplied ID) must never be regenerated.
        when(teacherRepository.findByTeacherIdAndSchoolId("EMP123", SCHOOL_ID)).thenReturn(Optional.empty());
        when(teacherRepository.existsById("EMP123")).thenReturn(false);

        Teacher saved = service.addTeacher(newTeacher("EMP123", LocalDate.of(1985, 3, 20)), request);

        assertThat(saved.getTeacherId()).isEqualTo("EMP123");
        verify(idGeneratorService, never()).generateTeacherId();
    }

    // ─── Class + Section responsibility validation ─────────────────────────

    private static final Long CLASS_12_ID = 12L;
    private static final Long SCIENCE_ID = 100L;
    private static final Long COMMERCE_ID = 200L;

    private SchoolClass class12() {
        SchoolClass c = new SchoolClass();
        c.setId(CLASS_12_ID);
        c.setName("12");
        return c;
    }

    private Section section(Long id, String name, boolean active) {
        Section s = new Section();
        s.setId(id);
        s.setName(name);
        s.setActive(active);
        return s;
    }

    private Teacher teacherWithResponsibility(String classTeacher, Long sectionId) {
        Teacher t = newTeacher("T1", LocalDate.of(1985, 3, 20));
        t.setClassTeacher(classTeacher);
        t.setClassTeacherSectionId(sectionId);
        return t;
    }

    @Test
    void blankClassTeacher_clearsBothFields() {
        Teacher t = teacherWithResponsibility(null, 999L); // stray section with no class — must be wiped too
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(teacherRepository.existsById("T1")).thenReturn(false);

        Teacher saved = service.addTeacher(t, request);

        assertThat(saved.getClassTeacher()).isNull();
        assertThat(saved.getClassTeacherSectionId()).isNull();
    }

    @Test
    void classTeacher_forUnknownClass_rejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "99")).thenReturn(Optional.empty());
        Teacher t = teacherWithResponsibility("99", null);

        assertThatThrownBy(() -> service.addTeacher(t, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void classWithSections_missingSectionId_rejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(class12()));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(java.util.List.of(section(SCIENCE_ID, "Science", true), section(COMMERCE_ID, "Commerce", true)));
        Teacher t = teacherWithResponsibility("12", null);

        assertThatThrownBy(() -> service.addTeacher(t, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("section");
    }

    @Test
    void classWithSections_validSection_accepted() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(class12()));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(java.util.List.of(section(SCIENCE_ID, "Science", true), section(COMMERCE_ID, "Commerce", true)));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(teacherRepository.existsById("T1")).thenReturn(false);
        Teacher t = teacherWithResponsibility("12", SCIENCE_ID);

        Teacher saved = service.addTeacher(t, request);

        assertThat(saved.getClassTeacherSectionId()).isEqualTo(SCIENCE_ID);
    }

    @Test
    void sectionBelongingToDifferentClass_rejected() {
        // Section 999 exists for a DIFFERENT class (not 12) — must never be accepted for class 12.
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(class12()));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(java.util.List.of(section(SCIENCE_ID, "Science", true)));
        Teacher t = teacherWithResponsibility("12", 999L);

        assertThatThrownBy(() -> service.addTeacher(t, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void inactiveSection_rejected() {
        // The active=true query itself excludes inactive sections, so an inactive section's id
        // is simply never present in the allowed set — proving it can never be accepted.
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(class12()));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(java.util.List.of(section(SCIENCE_ID, "Science", true)));
        Teacher t = teacherWithResponsibility("12", 777L); // the inactive section's id, not returned above

        assertThatThrownBy(() -> service.addTeacher(t, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classWithNoSections_sectionIdSupplied_rejectedRatherThanSilentlyDropped() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(schoolClass(10L, "10")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 10L, true))
                .thenReturn(java.util.List.of());
        Teacher t = teacherWithResponsibility("10", 123L);

        assertThatThrownBy(() -> service.addTeacher(t, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no configured sections");
    }

    @Test
    void classWithNoSections_noSectionIdSupplied_accepted() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(schoolClass(10L, "10")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 10L, true))
                .thenReturn(java.util.List.of());
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(teacherRepository.existsById("T1")).thenReturn(false);
        Teacher t = teacherWithResponsibility("10", null);

        Teacher saved = service.addTeacher(t, request);

        assertThat(saved.getClassTeacher()).isEqualTo("10");
        assertThat(saved.getClassTeacherSectionId()).isNull();
    }

    @Test
    void update_changingClassDoesNotCarryOverStaleIncompatibleSection() {
        // Request changes the teacher to Class 10 (no sections) but the request body still
        // (incorrectly) carries the old Science section id — must be rejected, not silently
        // accepted or silently cleared. validateAndNormalizeClassResponsibility() validates the
        // incoming request body directly, before ever fetching the existing teacher record, so
        // no existing-teacher stub is needed here.
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(schoolClass(10L, "10")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 10L, true))
                .thenReturn(java.util.List.of());

        Teacher incoming = teacherWithResponsibility("10", SCIENCE_ID); // stale section id from the old class

        assertThatThrownBy(() -> service.updateTeacher(incoming, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SchoolClass schoolClass(Long id, String name) {
        SchoolClass c = new SchoolClass();
        c.setId(id);
        c.setName(name);
        return c;
    }
}
