package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.StudentEnrollmentBackfillDetail;
import com.indraacademy.ias_management.dto.StudentEnrollmentBackfillReport;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class StudentEnrollmentBackfillService {

    private static final Logger log = LoggerFactory.getLogger(StudentEnrollmentBackfillService.class);

    private final StudentRepository studentRepository;
    private final StudentEnrollmentBackfillWorker worker;

    public StudentEnrollmentBackfillService(
            StudentRepository studentRepository,
            StudentEnrollmentBackfillWorker worker) {
        this.studentRepository = studentRepository;
        this.worker = worker;
    }

    /**
     * Explicit maintenance operation. This coordinator deliberately has no surrounding
     * transaction: each student is evaluated/created in the worker's independent transaction,
     * so one malformed legacy row cannot roll back other students' successful enrollments.
     */
    public StudentEnrollmentBackfillReport backfillForSchool(
            Long schoolId, LocalDate asOfDate, boolean dryRun) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (asOfDate == null) {
            throw new IllegalArgumentException("asOfDate is required");
        }

        List<Student> students = studentRepository.findBySchoolId(schoolId);
        Counters counters = new Counters();
        List<StudentEnrollmentBackfillDetail> details = new ArrayList<>(students.size());

        for (Student student : students) {
            counters.scanned++;
            String studentId = student.getStudentId();
            StudentEnrollmentBackfillWorker.Evaluation evaluation;
            try {
                evaluation = dryRun
                        ? worker.evaluate(schoolId, studentId, asOfDate)
                        : worker.create(schoolId, studentId, asOfDate);
            } catch (RuntimeException ex) {
                counters.failures++;
                details.add(new StudentEnrollmentBackfillDetail(
                        studentId, StudentEnrollmentBackfillWorker.Outcome.FAILURE.name(),
                        "Enrollment persistence failed; inspect server logs"));
                log.error("Enrollment backfill failed for schoolId={}, studentId={}",
                        schoolId, studentId, ex);
                continue;
            }

            count(evaluation.outcome(), counters);
            details.add(new StudentEnrollmentBackfillDetail(
                    studentId, evaluation.outcome().name(), evaluation.reason()));
        }

        StudentEnrollmentBackfillReport report = new StudentEnrollmentBackfillReport(
                schoolId, asOfDate, dryRun,
                counters.scanned, counters.eligible, counters.created, counters.alreadyPresent,
                counters.skippedNoSession, counters.skippedInvalidClass,
                counters.skippedInvalidSection, counters.skippedStatus,
                counters.skippedInvalidDate, counters.conflicts, counters.failures,
                List.copyOf(details));
        log.info("Enrollment backfill completed: schoolId={}, asOfDate={}, dryRun={}, " +
                        "scanned={}, eligible={}, created={}, alreadyPresent={}, conflicts={}, failures={}",
                schoolId, asOfDate, dryRun, report.scanned(), report.eligible(), report.created(),
                report.alreadyPresent(), report.conflicts(), report.failures());
        return report;
    }

    private void count(
            StudentEnrollmentBackfillWorker.Outcome outcome,
            Counters counters) {
        switch (outcome) {
            case ELIGIBLE -> counters.eligible++;
            case CREATED -> {
                counters.eligible++;
                counters.created++;
            }
            case ALREADY_PRESENT -> counters.alreadyPresent++;
            case SKIPPED_NO_SESSION -> counters.skippedNoSession++;
            case SKIPPED_INVALID_CLASS -> counters.skippedInvalidClass++;
            case SKIPPED_INVALID_SECTION -> counters.skippedInvalidSection++;
            case SKIPPED_STATUS -> counters.skippedStatus++;
            case SKIPPED_INVALID_DATE -> counters.skippedInvalidDate++;
            case CONFLICT -> counters.conflicts++;
            case FAILURE -> counters.failures++;
        }
    }

    private static final class Counters {
        private int scanned;
        private int eligible;
        private int created;
        private int alreadyPresent;
        private int skippedNoSession;
        private int skippedInvalidClass;
        private int skippedInvalidSection;
        private int skippedStatus;
        private int skippedInvalidDate;
        private int conflicts;
        private int failures;
    }
}
