package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.LeaveRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
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
 * SchoolClass is meant to be the single source of truth for which classes exist for a
 * school. These tests lock in that invariant for student create/update: a student can
 * never be saved against a free-text className with no matching SchoolClass row, and a
 * supplied sectionId must belong to both the resolved school and the resolved class.
 * This is the fix for the E2E audit finding where fee rules and a student were created
 * against class "10" with zero SchoolClass rows ever having been configured.
 */
@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private StudentFeesService studentFeesService;
    @Mock private SchoolRepository schoolRepository;
    @Mock private UserDetailsServiceImpl userDetailsService;
    @Mock private SecurityUtil securityUtil;
    @Mock private AuditService auditService;
    @Mock private EntitlementService entitlementService;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private LeaveRepository leaveRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private HttpServletRequest request;

    private StudentService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new StudentService();
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "studentFeesService", studentFeesService);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "userDetailsService", userDetailsService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "entitlementService", entitlementService);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "attendanceRepository", attendanceRepository);
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "leaveRepository", leaveRepository);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.empty());
    }

    private SchoolClass schoolClass(Long id, Long schoolId, String name) {
        SchoolClass sc = new SchoolClass();
        sc.setId(id);
        sc.setSchoolId(schoolId);
        sc.setName(name);
        sc.setActive(true);
        return sc;
    }

    private Section section(Long id, Long schoolId, Long classId, String name) {
        Section s = new Section();
        s.setId(id);
        s.setSchoolId(schoolId);
        s.setClassId(classId);
        s.setName(name);
        s.setActive(true);
        return s;
    }

    private Student newStudent(String studentId, String className, Long sectionId) {
        Student s = new Student();
        s.setStudentId(studentId);
        s.setName("Test Student");
        s.setClassName(className);
        s.setSectionId(sectionId);
        s.setJoiningDate(LocalDate.now());
        return s;
    }

    private void stubNewStudentId(String studentId) {
        when(studentRepository.findByStudentIdAndSchoolId(studentId, SCHOOL_ID)).thenReturn(Optional.empty());
        when(studentRepository.existsById(studentId)).thenReturn(false);
    }

    // ─── addStudent ─────────────────────────────────────────────────────────

    @Test
    void studentCreationWithValidConfiguredClassSucceeds() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("S1");

        Student saved = service.addStudent(newStudent("S1", "10", null), request);

        assertThat(saved.getClassId()).isEqualTo(10L);
        assertThat(saved.getClassName()).isEqualTo("10");
        verify(studentRepository).save(any(Student.class));
    }

    @Test
    void nonexistentClassIsRejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.empty());
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");

        verify(studentRepository, never()).save(any());
    }

    @Test
    void classBelongingToAnotherSchoolIsRejected() {
        // findBySchoolIdAndName is itself scoped by schoolId, so a class row that exists
        // only under a different school never matches here — proves the lookup can't be
        // satisfied by a same-named class belonging to someone else's school.
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.empty());
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");
        verify(studentRepository, never()).save(any());
    }

    @Test
    void validSectionClassCombinationSucceeds() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        when(sectionRepository.findByIdAndSchoolId(100L, SCHOOL_ID))
                .thenReturn(Optional.of(section(100L, SCHOOL_ID, 10L, "10-A")));
        stubNewStudentId("S1");

        Student saved = service.addStudent(newStudent("S1", "10", 100L), request);

        assertThat(saved.getSectionId()).isEqualTo(100L);
        assertThat(saved.getSectionName()).isEqualTo("10-A");
    }

    @Test
    void sectionBelongingToAnotherClassIsRejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        // Section 200 genuinely exists for this school, but under class 99, not 10.
        when(sectionRepository.findByIdAndSchoolId(200L, SCHOOL_ID))
                .thenReturn(Optional.of(section(200L, SCHOOL_ID, 99L, "9-A")));
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", 200L), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to class");
        verify(studentRepository, never()).save(any());
    }

    @Test
    void sectionBelongingToAnotherSchoolIsRejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        // findByIdAndSchoolId is scoped by schoolId — a section belonging to a different
        // school never resolves here, regardless of whether the id itself exists.
        when(sectionRepository.findByIdAndSchoolId(300L, SCHOOL_ID)).thenReturn(Optional.empty());
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", 300L), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Section not found");
        verify(studentRepository, never()).save(any());
    }

    // ─── updateStudent ──────────────────────────────────────────────────────

    @Test
    void updateStudentWithValidConfiguredClassSucceeds() {
        Student existing = newStudent("S1", "9", null);
        existing.setSchoolId(SCHOOL_ID);
        existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));

        Student updated = service.updateStudent("S1", newStudent("S1", "10", null), null, request);

        assertThat(updated.getClassId()).isEqualTo(10L);
        assertThat(updated.getClassName()).isEqualTo("10");
    }

    @Test
    void updateStudentRejectsNonexistentClass() {
        Student existing = newStudent("S1", "9", null);
        existing.setSchoolId(SCHOOL_ID);
        existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStudent("S1", newStudent("S1", "99", null), null, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");
        verify(studentRepository, never()).save(any());
        // The class-change fee side effect must not fire for an invalid target class either.
        verify(studentFeesService, never()).updateStudentFeesForClassChange(any(), any());
    }
}
