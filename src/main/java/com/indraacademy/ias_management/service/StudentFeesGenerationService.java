package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StudentFeesGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesGenerationService.class);

    private static final List<String> DEFAULT_CLASS_SEQUENCE = List.of(
            "Play Group", "Nursery", "LKG", "UKG",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"
    );

    @Autowired private StudentRepository studentRepository;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private AuditService auditService;

    /**
     * Runs on the 1st of every month. For each active school, checks whether this
     * month is the month BEFORE their academic year starts (the "generation month").
     * If so, generates fee records for all returning students for the next academic year.
     *
     * Examples:
     *   April-start school  → generates on March 1
     *   July-start school   → generates on June 1
     *   January-start school → generates on December 1
     */
    @Transactional
    @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Kolkata")
    public void generateStudentFeesForNextYear() {
        LocalDate today = LocalDate.now();
        log.info("Monthly fee-generation check triggered for date: {}", today);

        List<School> activeSchools = schoolRepository.findAll().stream()
                .filter(School::isActive)
                .collect(Collectors.toList());

        // Load all active students once, grouped by school to avoid N+1 queries
        Map<Long, List<Student>> studentsBySchool = studentRepository.findByStatus(StudentStatus.ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(Student::getSchoolId));

        for (School school : activeSchools) {
            try {
                int startMonth = school.getAcademicYearStartMonth();
                // Generation month = the calendar month immediately before the academic year starts
                int generationMonth = ((startMonth - 2 + 12) % 12) + 1;

                if (today.getMonthValue() != generationMonth) continue;

                // Compute next academic year label
                // For non-January starts: next year begins in startMonth of THIS calendar year
                // For January start: next year begins in January of NEXT calendar year
                int nextStartYear = (startMonth > today.getMonthValue()) ? today.getYear() : today.getYear() + 1;
                String nextAcademicYear = nextStartYear + "-" + (nextStartYear + 1);

                log.info("School {} generating fees for academic year {}", school.getId(), nextAcademicYear);

                List<String> classSequence = getSchoolClassSequence(school.getId());
                List<Student> students = studentsBySchool.getOrDefault(school.getId(), List.of());

                int feesGeneratedCount = 0;
                for (Student student : students) {
                    // Skip students enrolled during the current calendar year — their fees
                    // are generated at registration time, not here.
                    if (student.getCreatedAt() != null && student.getCreatedAt().getYear() >= today.getYear()) {
                        log.debug("Skipping new enrollment: student {}", student.getStudentId());
                        continue;
                    }

                    String nextClass = determineNextClass(student.getClassName(), classSequence);
                    if (nextClass == null) {
                        log.info("Skipping graduating student {} (class {})", student.getStudentId(), student.getClassName());
                        continue;
                    }

                    // Skip if fees already generated (guard against double-runs)
                    if (studentFeesRepository.existsByStudentIdAndYearAndSchoolId(
                            student.getStudentId(), nextAcademicYear, school.getId())) {
                        log.debug("Fees already generated for student {} year {}", student.getStudentId(), nextAcademicYear);
                        continue;
                    }

                    for (int month = 1; month <= 12; month++) {
                        StudentFees studentFees = new StudentFees();
                        studentFees.setStudentId(student.getStudentId());
                        studentFees.setClassName(nextClass);
                        studentFees.setMonth(month);
                        studentFees.setPaid(false);
                        studentFees.setTakesBus(student.getTakesBus());
                        studentFees.setYear(nextAcademicYear);
                        studentFees.setDistance(student.getDistance());
                        studentFees.setManuallyPaid(false);
                        studentFees.setManualPaymentReceived(null);
                        studentFees.setSchoolId(school.getId());
                        studentFeesRepository.save(studentFees);
                    }

                    log.debug("Generated 12 months of fees for student {} (class {}) for year {}",
                            student.getStudentId(), nextClass, nextAcademicYear);
                    feesGeneratedCount++;
                }

                auditService.log(
                        "SYSTEM", "SYSTEM",
                        "GENERATE_STUDENT_FEES",
                        "StudentFees",
                        nextAcademicYear,
                        null,
                        "School " + school.getId() + ": generated fees for " + feesGeneratedCount + " students",
                        "SYSTEM"
                );

                log.info("School {}: fee generation complete — {} students for year {}",
                        school.getId(), feesGeneratedCount, nextAcademicYear);

            } catch (DataAccessException e) {
                log.error("DB error generating fees for school {}", school.getId(), e);
            } catch (Exception e) {
                log.error("Unexpected error generating fees for school {}", school.getId(), e);
            }
        }
    }

    private List<String> getSchoolClassSequence(Long schoolId) {
        List<String> classes = schoolClassRepository
                .findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)
                .stream()
                .map(c -> c.getName())
                .collect(Collectors.toList());
        return classes.isEmpty() ? DEFAULT_CLASS_SEQUENCE : classes;
    }

    private String determineNextClass(String currentClass, List<String> classSequence) {
        if (currentClass == null || currentClass.trim().isEmpty()) return null;
        for (int i = 0; i < classSequence.size(); i++) {
            if (classSequence.get(i).equalsIgnoreCase(currentClass)) {
                return (i == classSequence.size() - 1) ? null : classSequence.get(i + 1);
            }
        }
        log.warn("Class '{}' not found in school's class sequence", currentClass);
        return null;
    }
}
