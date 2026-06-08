package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.dto.StudentLeaveDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;

@Service
public class StudentPromotionService {

    private static final Logger log = LoggerFactory.getLogger(StudentPromotionService.class);

    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;

    /**
     * Returns the school's active class names in display order (from DB).
     * Falls back to a default Indian sequence if the school has no classes configured.
     */
    private List<String> getSchoolClassSequence() {
        Long schoolId = securityUtil.getSchoolId();
        List<String> classes = schoolClassRepository
                .findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)
                .stream()
                .map(c -> c.getName())
                .collect(Collectors.toList());
        if (classes.isEmpty()) {
            // Fallback for schools with no class management data
            return List.of("Play Group", "Nursery", "LKG", "UKG",
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        }
        return classes;
    }

    // ─── Preview ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PromotionPreviewDTO> getPromotionPreview() {
        List<Student> activeStudents = studentRepository.findByStatusAndSchoolId(StudentStatus.ACTIVE, securityUtil.getSchoolId());

        List<String> classSequence = getSchoolClassSequence();
        Map<String, List<Student>> byClass = activeStudents.stream()
                .collect(Collectors.groupingBy(Student::getClassName));

        return byClass.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> classOrder(e.getKey(), classSequence)))
                .map(e -> {
                    List<StudentLeaveDTO> stubs = e.getValue().stream()
                            .sorted(Comparator.comparing(Student::getName))
                            .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName()))
                            .collect(Collectors.toList());
                    return new PromotionPreviewDTO(e.getKey(), stubs);
                })
                .collect(Collectors.toList());
    }

    // ─── Execute ──────────────────────────────────────────────────────────────

    @Transactional
    public PromotionResultDTO executePromotion(PromotionDecisionRequest request,
                                               HttpServletRequest httpRequest) {
        int promoted = 0;
        int detained = 0;
        int passedOut = 0;
        List<PromotionResultDTO.PromotionError> errors = new ArrayList<>();

        Long schoolId = securityUtil.getSchoolId();

        // Pre-load class sequence, class-by-name map, and sections-by-classId map
        // to avoid N+1 queries inside the loop
        List<String> seq = getSchoolClassSequence();
        Map<String, SchoolClass> classByName = schoolClassRepository
                .findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)
                .stream().collect(Collectors.toMap(SchoolClass::getName, c -> c, (a, b) -> a));
        Map<Long, List<Section>> sectionsByClassId = sectionRepository
                .findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)
                .stream().collect(Collectors.groupingBy(Section::getClassId));

        for (PromotionDecisionRequest.Decision decision : request.getDecisions()) {
            String studentId = decision.getStudentId();
            String action    = decision.getAction();

            try {
                Optional<Student> opt = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId);
                if (opt.isEmpty()) {
                    errors.add(new PromotionResultDTO.PromotionError(studentId, "Student not found"));
                    continue;
                }

                Student student  = opt.get();
                String oldClass  = student.getClassName();

                if ("PROMOTE".equalsIgnoreCase(action)) {
                    String nextClass = determineNextClass(oldClass, seq);
                    if (nextClass == null) {
                        errors.add(new PromotionResultDTO.PromotionError(studentId,
                                "Cannot promote: '" + oldClass + "' is the final class in the sequence"));
                        continue;
                    }
                    student.setClassName(nextClass);

                    // Dual-write: resolve className → classId, then handle section
                    SchoolClass targetClass = classByName.get(nextClass);
                    if (targetClass != null) {
                        Long newClassId = targetClass.getId();
                        student.setClassId(newClassId);

                        // Resolve section for the target class using pre-loaded data
                        List<Section> targetSections = sectionsByClassId.getOrDefault(newClassId, List.of());
                        if (targetSections.isEmpty()) {
                            student.setSectionId(null);
                            student.setSectionName(null);
                        } else if (student.getSectionName() != null) {
                            Optional<Section> matched = targetSections.stream()
                                    .filter(s -> s.getName().equalsIgnoreCase(student.getSectionName()))
                                    .findFirst();
                            if (matched.isPresent()) {
                                student.setSectionId(matched.get().getId());
                                student.setSectionName(matched.get().getName());
                            } else {
                                student.setSectionId(null);
                                student.setSectionName(null);
                            }
                        }
                    }

                    studentRepository.save(student);
                    auditService.log(
                            securityUtil.getUsername(), securityUtil.getRole(),
                            "PROMOTE_STUDENT", "Student", studentId,
                            oldClass, nextClass, httpRequest.getRemoteAddr());
                    promoted++;
                    log.info("Promoted student {} from {} → {}", studentId, oldClass, nextClass);

                } else if ("DETAIN".equalsIgnoreCase(action)) {
                    // No DB change needed — student stays in current class
                    detained++;
                    log.info("Detained student {} in {}", studentId, oldClass);

                } else if ("PASS_OUT".equalsIgnoreCase(action)) {
                    student.setStatus(StudentStatus.GRADUATED);
                    student.setReasonForLeaving("Completed final year");
                    if (student.getLeavingDate() == null) {
                        student.setLeavingDate(java.time.LocalDate.now());
                    }
                    studentRepository.save(student);
                    auditService.log(
                            securityUtil.getUsername(), securityUtil.getRole(),
                            "PASS_OUT_STUDENT", "Student", studentId,
                            "ACTIVE", "GRADUATED", httpRequest.getRemoteAddr());
                    passedOut++;
                    log.info("Graduated student {} (class {})", studentId, oldClass);

                } else {
                    errors.add(new PromotionResultDTO.PromotionError(studentId,
                            "Unknown action: '" + action + "'. Valid values: PROMOTE, DETAIN, PASS_OUT"));
                }

            } catch (Exception e) {
                log.error("Error processing promotion for student {}: {}", studentId, e.getMessage());
                errors.add(new PromotionResultDTO.PromotionError(studentId,
                        "Processing error: " + e.getMessage()));
            }
        }

        log.info("Promotion batch complete — promoted: {}, detained: {}, passedOut: {}, errors: {}",
                promoted, detained, passedOut, errors.size());
        return new PromotionResultDTO(promoted, detained, passedOut, errors);
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    /**
     * Clears section assignments for students whose sectionId belongs to a different class
     * than their current class — a state that can occur if promotion happened before
     * section-aware logic was in place.
     *
     * @return number of students whose orphaned section was cleared
     */
    @Transactional
    public int fixOrphanedSections() {
        Long schoolId = securityUtil.getSchoolId();
        int affected = studentRepository.clearOrphanedSections(schoolId);
        if (affected > 0) {
            log.warn("Cleared orphaned section assignments for {} student(s) in school {}",
                    affected, schoolId);
        }
        return affected;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the class that follows {@code currentClass} in the school's class sequence,
     * or {@code null} if it is the last class or unrecognised.
     */
    private String determineNextClass(String currentClass, List<String> classSequence) {
        if (currentClass == null) return null;
        int idx = indexOfClass(currentClass, classSequence);
        if (idx < 0 || idx >= classSequence.size() - 1) return null;
        return classSequence.get(idx + 1);
    }

    /**
     * Returns a sort key for the class name based on the school's class order.
     * Unrecognised classes sort to the end.
     */
    private int classOrder(String className, List<String> classSequence) {
        int idx = indexOfClass(className, classSequence);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /** Case-insensitive lookup of {@code className} in the provided sequence. */
    private int indexOfClass(String className, List<String> classSequence) {
        if (className == null) return -1;
        String normalised = className.trim();
        for (int i = 0; i < classSequence.size(); i++) {
            if (classSequence.get(i).equalsIgnoreCase(normalised)) return i;
        }
        return -1;
    }
}
