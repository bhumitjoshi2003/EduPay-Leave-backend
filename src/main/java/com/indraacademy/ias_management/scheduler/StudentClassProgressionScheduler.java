package com.indraacademy.ias_management.scheduler;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class StudentClassProgressionScheduler {

    private static final Logger log = LoggerFactory.getLogger(StudentClassProgressionScheduler.class);

    private static final List<String> DEFAULT_CLASS_SEQUENCE = List.of(
            "Play Group", "Nursery", "LKG", "UKG",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"
    );

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private SchoolClassRepository schoolClassRepository;

    @Autowired
    private SchoolRepository schoolRepository;

    /**
     * Guards against concurrent execution within the same JVM instance.
     * For multi-instance deployments (horizontal scaling) use ShedLock or
     * a similar distributed lock to prevent simultaneous runs across nodes.
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Runs on the 26th of every calendar month (a few days before each school's own academic
     * year starts) and promotes students to the next class for whichever schools that day
     * actually applies to — mirroring StudentFeesGenerationService.generateStudentFeesForNextYear's
     * per-school gating on academicYearStartMonth, rather than a single fixed calendar date.
     *
     * Was previously hardcoded to run once a year, on 26th March only — correct for the
     * April-default school (26th is a few days before April 1) but wrong for every other
     * start month: a July-start school got promoted 4 months early, a January-start school
     * ~2 months late, and so on for any other configured start month.
     *
     * Uses each school's configured class sequence from the database.
     */
    @Transactional
    @Scheduled(cron = "0 0 0 26 * *", zone = "Asia/Kolkata")
    public void incrementStudentClasses() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("StudentClassProgressionScheduler is already running — skipping this trigger.");
            return;
        }
        log.info("Starting scheduled student class progression check.");

        try {
            LocalDate today = LocalDate.now();

            List<School> activeSchools = schoolRepository.findAll().stream()
                    .filter(School::isActive)
                    .collect(Collectors.toList());

            List<Student> students = studentRepository.findByStatus(StudentStatus.ACTIVE);
            Map<Long, List<Student>> bySchool = students.stream()
                    .collect(Collectors.groupingBy(Student::getSchoolId));

            int promotedCount = 0;

            for (School school : activeSchools) {
                int startMonth = school.getAcademicYearStartMonth();
                int promotionMonth = computePromotionMonth(startMonth);
                if (today.getMonthValue() != promotionMonth) continue;

                Long schoolId = school.getId();
                List<String> classSequence = getSchoolClassSequence(schoolId);

                for (Student student : bySchool.getOrDefault(schoolId, List.of())) {
                    String currentClass = student.getClassName();
                    String nextClass = determineNextClass(currentClass, classSequence);

                    if (nextClass == null) {
                        log.info("Skipping student ID {} — final class reached ({})",
                                student.getStudentId(), currentClass);
                        continue;
                    }

                    log.debug("Promoting {} from {} → {}",
                            student.getStudentId(), currentClass, nextClass);

                    student.setClassName(nextClass);
                    studentRepository.save(student);
                    promotedCount++;
                }
            }

            log.info("Promotion completed. Total students promoted: {}", promotedCount);

        } catch (DataAccessException e) {
            log.error("Database error during student promotion", e);
        } catch (Exception e) {
            log.error("Unexpected error during student promotion", e);
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * The calendar month immediately before a school's academic year starts — same formula
     * StudentFeesGenerationService.generateStudentFeesForNextYear uses for its own generation-
     * month gating, so both jobs fire in the same calendar month for a given school.
     * Package-private so it's directly unit-testable.
     */
    int computePromotionMonth(int startMonth) {
        return ((startMonth - 2 + 12) % 12) + 1;
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
        int idx = -1;
        for (int i = 0; i < classSequence.size(); i++) {
            if (classSequence.get(i).equalsIgnoreCase(currentClass)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            log.warn("Class '{}' not found in school's class sequence — skipping promotion.", currentClass);
            return null;
        }
        // Last class in sequence → graduated
        if (idx == classSequence.size() - 1) return null;
        return classSequence.get(idx + 1);
    }
}
