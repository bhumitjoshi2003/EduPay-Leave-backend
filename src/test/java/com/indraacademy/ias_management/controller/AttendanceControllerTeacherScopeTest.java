package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end (controller → real TeacherClassScopeService → mocked repositories) proof of the
 * exact scenario this whole fix exists for: Class 12 has Science and Commerce sections, Teacher
 * A is the Science class-teacher, Teacher B is the Commerce class-teacher. Neither can access or
 * mark the other's section, a client-supplied sectionId can never widen or redirect scope, and
 * ADMIN remains fully unrestricted.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceControllerTeacherScopeTest {

    @Mock private AttendanceService attendanceService;
    @Mock private AuthService authService;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private ParentPortalService parentPortalService;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private HttpServletRequest request;

    private AttendanceController controller;

    private static final Long SCHOOL_ID = 1L;
    private static final Long CLASS_12_ID = 12L;
    private static final Long SCIENCE_ID = 100L;
    private static final Long COMMERCE_ID = 200L;

    @BeforeEach
    void setUp() {
        TeacherClassScopeService scopeService = new TeacherClassScopeService();
        ReflectionTestUtils.setField(scopeService, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(scopeService, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(scopeService, "sectionRepository", sectionRepository);

        controller = new AttendanceController();
        ReflectionTestUtils.setField(controller, "attendanceService", attendanceService);
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(controller, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(controller, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(controller, "parentPortalService", parentPortalService);
        ReflectionTestUtils.setField(controller, "teacherClassScopeService", scopeService);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);

        SchoolClass class12 = new SchoolClass();
        class12.setId(CLASS_12_ID);
        class12.setName("12");
        lenient().when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(class12));

        Section science = new Section();
        science.setId(SCIENCE_ID); science.setName("Science"); science.setActive(true);
        Section commerce = new Section();
        commerce.setId(COMMERCE_ID); commerce.setName("Commerce"); commerce.setActive(true);
        lenient().when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(List.of(science, commerce));

        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("TeacherB", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherB", "12", COMMERCE_ID)));
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("Legacy", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("Legacy", "12", null)));
    }

    private Teacher teacher(String id, String classTeacher, Long sectionId) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setClassTeacher(classTeacher);
        t.setClassTeacherSectionId(sectionId);
        return t;
    }

    private Attendance row(String studentId, String className) {
        Attendance a = new Attendance();
        a.setStudentId(studentId);
        a.setClassName(className);
        a.setDate(LocalDate.of(2026, 8, 20));
        a.setStatus("ABSENT");
        return a;
    }

    private void actingAs(String role, String userId) {
        when(authService.getRole()).thenReturn(role);
        lenient().when(authService.getUserId()).thenReturn(userId);
    }

    // ── saveAttendance ───────────────────────────────────────────────────────

    @Test
    void teacherA_savesAttendanceForOwnClass_effectiveSectionIsScience() {
        actingAs("TEACHER", "TeacherA");
        List<Attendance> rows = List.of(row("S1", "12"), row("X", "12"));

        controller.saveAttendance(rows, null, request);

        verify(attendanceService).saveAttendance(eq(rows), eq(SCIENCE_ID), eq(request));
    }

    @Test
    void teacherA_cannotSaveAttendance_forDifferentClass() {
        actingAs("TEACHER", "TeacherA");
        List<Attendance> rows = List.of(row("S1", "10"));

        ResponseEntity<String> response = controller.saveAttendance(rows, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void teacherA_clientSuppliedCommerceSectionId_isIgnored_scienceUsedInstead() {
        actingAs("TEACHER", "TeacherA");
        List<Attendance> rows = List.of(row("S1", "12"));

        // Teacher A tries to save "for" Commerce by passing Commerce's sectionId directly.
        controller.saveAttendance(rows, COMMERCE_ID, request);

        verify(attendanceService).saveAttendance(eq(rows), eq(SCIENCE_ID), eq(request));
    }

    @Test
    void legacyAmbiguousTeacher_cannotSaveAttendance_getsActionableError() {
        actingAs("TEACHER", "Legacy");
        List<Attendance> rows = List.of(row("S1", "12"));

        ResponseEntity<String> response = controller.saveAttendance(rows, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(TeacherClassScopeService.SECTION_REQUIRED_MESSAGE);
    }

    // ── getAttendanceByDateAndClass ──────────────────────────────────────────

    @Test
    void teacherA_getsOwnSectionAttendance_regardlessOfClientSectionIdParam() {
        actingAs("TEACHER", "TeacherA");
        when(attendanceService.getAttendanceByDateAndClass(any(), eq("12"), eq(SCIENCE_ID))).thenReturn(List.of());

        ResponseEntity<?> response = controller.getAttendanceByDateAndClass(LocalDate.of(2026, 8, 20), "12", COMMERCE_ID);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(attendanceService).getAttendanceByDateAndClass(any(), eq("12"), eq(SCIENCE_ID));
    }

    @Test
    void teacherA_omittingSectionId_stillScopedToScience_notWholeClass() {
        actingAs("TEACHER", "TeacherA");
        when(attendanceService.getAttendanceByDateAndClass(any(), eq("12"), eq(SCIENCE_ID))).thenReturn(List.of());

        controller.getAttendanceByDateAndClass(LocalDate.of(2026, 8, 20), "12", null);

        verify(attendanceService).getAttendanceByDateAndClass(any(), eq("12"), eq(SCIENCE_ID));
    }

    @Test
    void teacherB_getsCommerceAttendance_neverScience() {
        actingAs("TEACHER", "TeacherB");
        when(attendanceService.getAttendanceByDateAndClass(any(), eq("12"), eq(COMMERCE_ID))).thenReturn(List.of());

        controller.getAttendanceByDateAndClass(LocalDate.of(2026, 8, 20), "12", null);

        verify(attendanceService).getAttendanceByDateAndClass(any(), eq("12"), eq(COMMERCE_ID));
    }

    @Test
    void admin_retainsFullAccess_clientSectionIdRespectedAsIs() {
        actingAs("ADMIN", "adminUser");
        when(attendanceService.getAttendanceByDateAndClass(any(), eq("12"), eq(COMMERCE_ID))).thenReturn(List.of());

        ResponseEntity<?> response = controller.getAttendanceByDateAndClass(LocalDate.of(2026, 8, 20), "12", COMMERCE_ID);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(attendanceService).getAttendanceByDateAndClass(any(), eq("12"), eq(COMMERCE_ID));
    }

    @Test
    void classWithNoSections_teacherAccessUnaffected_preservesOriginalBehavior() {
        SchoolClass class10 = new SchoolClass();
        class10.setId(10L); class10.setName("10");
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(class10));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 10L, true))
                .thenReturn(List.of());
        when(teacherRepository.findByTeacherIdAndSchoolId("T10", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T10", "10", null)));
        actingAs("TEACHER", "T10");
        when(attendanceService.getAttendanceByDateAndClass(any(), eq("10"), eq((Long) null))).thenReturn(List.of());

        ResponseEntity<?> response = controller.getAttendanceByDateAndClass(LocalDate.of(2026, 8, 20), "10", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(attendanceService).getAttendanceByDateAndClass(any(), eq("10"), eq((Long) null));
    }

    // ── checkStudentDataAccess (via getAttendanceCounts) — per-student section check ────────

    @Test
    void teacherA_canViewCountsForOwnScienceStudent() {
        actingAs("TEACHER", "TeacherA");
        when(studentRepository.findByStudentIdAndSchoolId("S-SCI", SCHOOL_ID))
                .thenReturn(Optional.of(scienceStudent("S-SCI")));

        ResponseEntity<?> response = controller.getAttendanceCounts("S-SCI", 2026, 8);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void teacherA_cannotViewCountsForCommerceStudent_evenSameClass() {
        actingAs("TEACHER", "TeacherA");
        when(studentRepository.findByStudentIdAndSchoolId("S-COM", SCHOOL_ID))
                .thenReturn(Optional.of(commerceStudent("S-COM")));

        ResponseEntity<?> response = controller.getAttendanceCounts("S-COM", 2026, 8);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void teacherB_cannotViewCountsForScienceStudent() {
        actingAs("TEACHER", "TeacherB");
        when(studentRepository.findByStudentIdAndSchoolId("S-SCI", SCHOOL_ID))
                .thenReturn(Optional.of(scienceStudent("S-SCI")));

        ResponseEntity<?> response = controller.getAttendanceCounts("S-SCI", 2026, 8);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void legacyAmbiguousTeacher_cannotViewAnyStudentCounts_untilSectionAssigned() {
        actingAs("TEACHER", "Legacy");
        when(studentRepository.findByStudentIdAndSchoolId("S-SCI", SCHOOL_ID))
                .thenReturn(Optional.of(scienceStudent("S-SCI")));

        ResponseEntity<?> response = controller.getAttendanceCounts("S-SCI", 2026, 8);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private Student scienceStudent(String id) {
        Student s = new Student();
        s.setStudentId(id);
        s.setClassName("12");
        s.setSectionId(SCIENCE_ID);
        return s;
    }

    private Student commerceStudent(String id) {
        Student s = new Student();
        s.setStudentId(id);
        s.setClassName("12");
        s.setSectionId(COMMERCE_ID);
        return s;
    }
}
