package com.indraacademy.ias_management.scheduler;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * Guards against concurrent execution within the same JVM instance.
     * For multi-instance deployments (horizontal scaling) use ShedLock or
     * a similar distributed lock to prevent simultaneous runs across nodes.
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Runs every year on 26th March at 00:00 to promote students to the next class.
     * Uses each school's configured class sequence from the database.
     */
    @Transactional
    @Scheduled(cron = "0 0 0 26 3 *", zone = "Asia/Kolkata")
    public void incrementStudentClasses() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("StudentClassProgressionScheduler is already running — skipping this trigger.");
            return;
        }
        log.info("Starting scheduled student class progression for new academic year.");

        try {
            List<Student> students = studentRepository.findByStatus(StudentStatus.ACTIVE);
            int promotedCount = 0;

            // Group students by school so we load each school's class sequence only once
            Map<Long, List<Student>> bySchool = students.stream()
                    .collect(Collectors.groupingBy(Student::getSchoolId));

            for (Map.Entry<Long, List<Student>> entry : bySchool.entrySet()) {
                Long schoolId = entry.getKey();
                List<String> classSequence = getSchoolClassSequence(schoolId);

                for (Student student : entry.getValue()) {
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
