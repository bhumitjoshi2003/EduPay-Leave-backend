package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.dto.StudentLeaveDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
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

@Service
public class StudentPromotionService {

    private static final Logger log = LoggerFactory.getLogger(StudentPromotionService.class);

    // Canonical progression order. classOrder() maps each name to its index.
    // Play Group → Nursery → LKG → UKG → 1 → 2 → … → 12
    private static final List<String> CLASS_SEQUENCE = List.of(
            "Play Group", "Nursery", "LKG", "UKG",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"
    );

    @Autowired private StudentRepository studentRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;

    // ─── Preview ─────────────────────────────────────────────────────────────

    public List<PromotionPreviewDTO> getPromotionPreview() {
        List<Student> activeStudents = studentRepository.findByStatus(StudentStatus.ACTIVE);

        Map<String, List<Student>> byClass = activeStudents.stream()
                .collect(Collectors.groupingBy(Student::getClassName));

        return byClass.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> classOrder(e.getKey())))
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

        for (PromotionDecisionRequest.Decision decision : request.getDecisions()) {
            String studentId = decision.getStudentId();
            String action    = decision.getAction();

            try {
                Optional<Student> opt = studentRepository.findByStudentId(studentId);
                if (opt.isEmpty()) {
                    errors.add(new PromotionResultDTO.PromotionError(studentId, "Student not found"));
                    continue;
                }

                Student student  = opt.get();
                String oldClass  = student.getClassName();

                if ("PROMOTE".equalsIgnoreCase(action)) {
                    String nextClass = determineNextClass(oldClass);
                    if (nextClass == null) {
                        errors.add(new PromotionResultDTO.PromotionError(studentId,
                                "Cannot promote: '" + oldClass + "' is the final class in the sequence"));
                        continue;
                    }
                    student.setClassName(nextClass);
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
                    student.setStatus(StudentStatus.INACTIVE);
                    studentRepository.save(student);
                    auditService.log(
                            securityUtil.getUsername(), securityUtil.getRole(),
                            "PASS_OUT_STUDENT", "Student", studentId,
                            "ACTIVE", "INACTIVE", httpRequest.getRemoteAddr());
                    passedOut++;
                    log.info("Passed out student {} (class {})", studentId, oldClass);

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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the class that follows {@code currentClass} in the progression sequence,
     * or {@code null} if {@code currentClass} is the last class (12) or unrecognised.
     */
    private String determineNextClass(String currentClass) {
        if (currentClass == null) return null;
        int idx = indexOfClass(currentClass);
        if (idx < 0 || idx >= CLASS_SEQUENCE.size() - 1) return null;
        return CLASS_SEQUENCE.get(idx + 1);
    }

    /**
     * Returns a sort key for the class name so groups appear in progression order.
     * Unrecognised classes sort to the end.
     */
    private int classOrder(String className) {
        int idx = indexOfClass(className);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /** Case-insensitive lookup of {@code className} in {@link #CLASS_SEQUENCE}. */
    private int indexOfClass(String className) {
        if (className == null) return -1;
        String normalised = className.trim();
        for (int i = 0; i < CLASS_SEQUENCE.size(); i++) {
            if (CLASS_SEQUENCE.get(i).equalsIgnoreCase(normalised)) return i;
        }
        return -1;
    }
}
