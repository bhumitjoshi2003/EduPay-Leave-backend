package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentEnrollmentBackfillServiceTest {

    @Mock StudentRepository studentRepository;
    @Mock StudentEnrollmentBackfillWorker worker;

    private StudentEnrollmentBackfillService service;

    @BeforeEach
    void setUp() {
        service = new StudentEnrollmentBackfillService(studentRepository, worker);
    }

    @Test
    void dryRunReportsEligibleButCallsOnlyEvaluation() {
        Student student = student("S1");
        when(studentRepository.findBySchoolId(1L)).thenReturn(List.of(student));
        when(worker.evaluate(1L, "S1", LocalDate.of(2026, 9, 6)))
                .thenReturn(new StudentEnrollmentBackfillWorker.Evaluation(
                        StudentEnrollmentBackfillWorker.Outcome.ELIGIBLE,
                        "Validated", new StudentEnrollment()));

        var report = service.backfillForSchool(1L, LocalDate.of(2026, 9, 6), true);

        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.eligible()).isEqualTo(1);
        assertThat(report.created()).isZero();
        verify(worker).evaluate(1L, "S1", LocalDate.of(2026, 9, 6));
    }

    @Test
    void oneFailureDoesNotPreventAnotherEligibleStudent() {
        when(studentRepository.findBySchoolId(1L)).thenReturn(List.of(student("BAD"), student("GOOD")));
        LocalDate date = LocalDate.of(2026, 9, 6);
        when(worker.create(1L, "BAD", date)).thenThrow(new IllegalStateException("bad legacy row"));
        when(worker.create(1L, "GOOD", date)).thenReturn(
                new StudentEnrollmentBackfillWorker.Evaluation(
                        StudentEnrollmentBackfillWorker.Outcome.CREATED,
                        "Created", new StudentEnrollment()));

        var report = service.backfillForSchool(1L, date, false);

        assertThat(report.scanned()).isEqualTo(2);
        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.eligible()).isEqualTo(1);
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.details()).extracting("studentId").containsExactly("BAD", "GOOD");
    }

    @Test
    void coordinatorReadsOnlyRequestedTenantStudents() {
        when(studentRepository.findBySchoolId(2L)).thenReturn(List.of());

        var report = service.backfillForSchool(2L, LocalDate.of(2026, 9, 6), true);

        assertThat(report.schoolId()).isEqualTo(2L);
        assertThat(report.scanned()).isZero();
        verify(studentRepository).findBySchoolId(2L);
    }

    private Student student(String id) {
        Student student = new Student();
        student.setStudentId(id);
        student.setSchoolId(1L);
        return student;
    }
}
