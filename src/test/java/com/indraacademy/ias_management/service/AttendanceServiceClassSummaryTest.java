package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Covers AttendanceService.getClassSummary — specifically that it fetches the roster via the
 * ACTIVE-status-filtered repository query rather than loading every student in the class and
 * filtering in memory. A student who has since left the school must never appear in a class
 * attendance summary, even if their old attendance rows are still on file.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceClassSummaryTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SecurityUtil securityUtil;

    private AttendanceService service;

    private static final Long SCHOOL_ID = 3L;
    private static final String CLASS_NAME = "8B";

    @BeforeEach
    void setUp() {
        service = new AttendanceService();
        ReflectionTestUtils.setField(service, "attendanceRepository", attendanceRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    private Student student(String id, String name, StudentStatus status) {
        Student s = new Student();
        s.setStudentId(id);
        s.setName(name);
        s.setClassName(CLASS_NAME);
        s.setStatus(status);
        return s;
    }

    @Test
    void classSummary_onlyQueriesActiveStudents_anExitedStudentNeverAppears() {
        // The roster the service actually asks for is the ACTIVE-only variant — a stub on the
        // old unfiltered findByClassNameAndSchoolId is deliberately absent, so a regression back
        // to that call would leave `students` empty (Mockito default) and fail loudly, not
        // silently include the withdrawn student.
        when(studentRepository.findByClassNameAndStatusAndSchoolId(CLASS_NAME, StudentStatus.ACTIVE, SCHOOL_ID))
                .thenReturn(List.of(student("S1", "Still Enrolled Sam", StudentStatus.ACTIVE)));

        LocalDate day = LocalDate.of(2026, 8, 10);
        Attendance sentinel = new Attendance();
        sentinel.setStudentId("X");
        sentinel.setDate(day);
        sentinel.setClassName(CLASS_NAME);
        sentinel.setSchoolId(SCHOOL_ID);
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(
                org.mockito.ArgumentMatchers.eq(CLASS_NAME), org.mockito.ArgumentMatchers.eq(SCHOOL_ID),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(sentinel));

        List<ClassAttendanceSummaryDTO> result =
                service.getClassSummary(CLASS_NAME, "month", 8, 2026, null, null);

        assertThat(result).extracting(ClassAttendanceSummaryDTO::getStudentId).containsExactly("S1");
    }
}
