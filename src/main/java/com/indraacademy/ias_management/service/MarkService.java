package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all mark entry, retrieval, and results computation.
 *
 * Rank algorithm: standard competition ranking ("1224" / sports ranking).
 *   rank = 1 + count of students who scored strictly more than this student.
 *   Ties share the same rank; the next rank after a tie group skips numbers.
 *
 * A rank of 0 indicates the student has no mark entered (null marksObtained).
 */
@Service
public class MarkService {

    private static final Logger log = LoggerFactory.getLogger(MarkService.class);

    /** Classes with stream-based, per-student subject lists. */
    private static final Set<String> UPPER_CLASSES = Set.of("11", "12");

    @Autowired private StudentMarkRepository studentMarkRepository;
    @Autowired private ExamSubjectEntryRepository examSubjectEntryRepository;
    @Autowired private ExamConfigRepository examConfigRepository;
    @Autowired private StudentStreamSelectionRepository studentStreamSelectionRepository;
    @Autowired private StreamCoreSubjectRepository streamCoreSubjectRepository;
    @Autowired private OptionalSubjectRepository optionalSubjectRepository;
    @Autowired private StudentService studentService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    // ─── Mark Entry Mode A: by subject ───────────────────────────────────────

    /**
     * Returns all students who should sit a given exam subject, with their current mark.
     * For classes 1–10: all active students in the class.
     * For classes 11–12: only students whose stream includes that subject.
     */
    @Transactional(readOnly = true)
    public List<StudentSubjectMarkDTO> getStudentsForSubjectEntry(Long examSubjectEntryId) {
        ExamSubjectEntry entry = examSubjectEntryRepository.findById(examSubjectEntryId)
                .orElseThrow(() -> new NoSuchElementException(
                        "ExamSubjectEntry not found: " + examSubjectEntryId));
        ExamConfig exam = examConfigRepository.findById(entry.getExamConfigId())
                .orElseThrow(() -> new NoSuchElementException(
                        "ExamConfig not found for entry " + examSubjectEntryId));

        List<Student> students = resolveStudentsForSubject(exam.getClassName(), entry.getSubjectName());

        Set<String> studentIds = students.stream()
                .map(Student::getStudentId).collect(Collectors.toSet());

        Map<String, Double> marksByStudent = studentMarkRepository
                .findByExamSubjectEntryId(examSubjectEntryId)
                .stream()
                .filter(m -> studentIds.contains(m.getStudentId()))
                .collect(Collectors.toMap(StudentMark::getStudentId, StudentMark::getMarksObtained));

        return students.stream()
                .map(s -> new StudentSubjectMarkDTO(
                        s.getStudentId(), s.getName(), marksByStudent.get(s.getStudentId())))
                .collect(Collectors.toList());
    }

    // ─── Mark Entry Mode B: by student ───────────────────────────────────────

    /**
     * Returns all subject entries in an exam with the given student's current marks.
     * For class 11/12, filters to only the student's subjects (stream + optional).
     */
    @Transactional(readOnly = true)
    public List<StudentExamSubjectDTO> getStudentMarksForExam(String studentId, Long examConfigId) {
        Student student = studentService.getStudent(studentId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigId(examConfigId);

        // For 11/12, restrict to the student's subjects
        if (UPPER_CLASSES.contains(student.getClassName())) {
            entries = entries.stream()
                    .filter(e -> studentHasSubject(studentId, e.getSubjectName()))
                    .collect(Collectors.toList());
        }

        List<Long> entryIds = entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList());

        Map<Long, Double> markByEntryId = studentMarkRepository
                .findByStudentIdAndExamSubjectEntryIdIn(studentId, entryIds)
                .stream()
                .collect(Collectors.toMap(StudentMark::getExamSubjectEntryId, StudentMark::getMarksObtained));

        return entries.stream()
                .map(e -> new StudentExamSubjectDTO(
                        e.getId(), e.getSubjectName(), e.getMaxMarks(), e.getExamDate(),
                        markByEntryId.get(e.getId())))
                .collect(Collectors.toList());
    }

    // ─── Bulk mark save (upsert) ──────────────────────────────────────────────

    @Transactional
    public MarkBulkResultDTO bulkSaveMarks(List<MarkEntryRequest> requests, HttpServletRequest httpRequest) {
        int saved = 0, updated = 0;
        List<MarkBulkResultDTO.MarkError> errors = new ArrayList<>();
        String callerUserId = securityUtil.getUsername();
        String callerRole   = securityUtil.getRole();
        String ip           = httpRequest.getRemoteAddr();

        for (MarkEntryRequest req : requests) {
            try {
                validateMarkEntry(req);

                Optional<StudentMark> existing = studentMarkRepository
                        .findByStudentIdAndExamSubjectEntryId(req.getStudentId(), req.getExamSubjectEntryId());

                if (existing.isPresent()) {
                    StudentMark mark = existing.get();
                    String oldJson = toJson(mark);
                    mark.setMarksObtained(req.getMarksObtained());
                    mark.setEnteredBy(callerUserId);
                    mark.setSchoolId(securityUtil.getSchoolId());
                    studentMarkRepository.save(mark);
                    auditService.logUpdate(callerUserId, callerRole, "UPDATE_STUDENT_MARK",
                            "STUDENT_MARK", mark.getId().toString(), oldJson, toJson(mark), ip);
                    updated++;
                } else {
                    StudentMark mark = new StudentMark();
                    mark.setStudentId(req.getStudentId());
                    mark.setExamSubjectEntryId(req.getExamSubjectEntryId());
                    mark.setMarksObtained(req.getMarksObtained());
                    mark.setEnteredBy(callerUserId);
                    mark.setSchoolId(securityUtil.getSchoolId());
                    studentMarkRepository.save(mark);
                    auditService.log(callerUserId, callerRole, "CREATE_STUDENT_MARK",
                            "STUDENT_MARK", mark.getId().toString(), null, toJson(mark), ip);
                    saved++;
                }

            } catch (IllegalArgumentException | NoSuchElementException e) {
                String sid = req != null && req.getStudentId() != null ? req.getStudentId() : "unknown";
                log.warn("Mark entry rejected for studentId={}: {}", sid, e.getMessage());
                errors.add(new MarkBulkResultDTO.MarkError(sid, e.getMessage()));
            } catch (Exception e) {
                String sid = req != null && req.getStudentId() != null ? req.getStudentId() : "unknown";
                log.error("Unexpected error saving mark for studentId={}", sid, e);
                errors.add(new MarkBulkResultDTO.MarkError(sid, "Unexpected error: " + e.getMessage()));
            }
        }

        return new MarkBulkResultDTO(saved, updated, errors);
    }

    // ─── Student results view ─────────────────────────────────────────────────

    /**
     * Full exam results for a student across all exams in a session (or all sessions if omitted).
     */
    @Transactional(readOnly = true)
    public List<ExamResultDTO> getStudentResults(String studentId, String session) {
        Student student = studentService.getStudent(studentId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        Long schoolId = securityUtil.getSchoolId();
        List<ExamConfig> exams = (session != null && !session.isBlank())
                ? examConfigRepository.findBySessionAndClassNameAndSchoolId(session, student.getClassName(), schoolId)
                : examConfigRepository.findByClassNameAndSchoolId(student.getClassName(), schoolId);

        List<ExamResultDTO> results = new ArrayList<>();

        for (ExamConfig exam : exams) {
            List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigId(exam.getId());
            if (entries.isEmpty()) continue;

            // For 11/12, show only the student's own subjects
            if (UPPER_CLASSES.contains(student.getClassName())) {
                entries = entries.stream()
                        .filter(e -> studentHasSubject(studentId, e.getSubjectName()))
                        .collect(Collectors.toList());
            }
            if (entries.isEmpty()) continue;

            List<Long> entryIds = entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList());

            // Student's marks
            Map<Long, Double> studentMarkMap = studentMarkRepository
                    .findByStudentIdAndExamSubjectEntryIdIn(studentId, entryIds)
                    .stream()
                    .collect(Collectors.toMap(StudentMark::getExamSubjectEntryId, StudentMark::getMarksObtained));

            // All marks for this exam (for class average + per-subject rank)
            List<StudentMark> allMarks = studentMarkRepository.findByExamSubjectEntryIdIn(entryIds);
            Map<Long, List<StudentMark>> marksByEntry = allMarks.stream()
                    .collect(Collectors.groupingBy(StudentMark::getExamSubjectEntryId));

            // Total per student for overall rank
            Map<String, Double> totalByStudent = computeTotalsByStudent(allMarks);

            double studentTotal = 0;
            double maxTotal = 0;
            List<SubjectResultDTO> subjectResults = new ArrayList<>();

            for (ExamSubjectEntry entry : entries) {
                Double marksObtained = studentMarkMap.get(entry.getId());
                List<StudentMark> entryMarks = marksByEntry.getOrDefault(entry.getId(), Collections.emptyList());

                double avg = entryMarks.stream()
                        .filter(m -> m.getMarksObtained() != null)
                        .mapToDouble(StudentMark::getMarksObtained)
                        .average().orElse(0.0);
                avg = round2(avg);

                int rank = computeRank(marksObtained, entryMarks);

                subjectResults.add(new SubjectResultDTO(
                        entry.getSubjectName(), entry.getMaxMarks(), entry.getExamDate(),
                        marksObtained, avg, rank));

                if (marksObtained != null) studentTotal += marksObtained;
                maxTotal += entry.getMaxMarks();
            }

            double percentage = maxTotal > 0 ? round2(studentTotal / maxTotal * 100) : 0.0;
            int overallRank = computeOverallRank(studentId, totalByStudent);

            results.add(new ExamResultDTO(
                    exam.getId(), exam.getExamName(), exam.getClassName(), exam.getSession(),
                    student.getName(), subjectResults, studentTotal, maxTotal, percentage, overallRank));
        }

        return results;
    }

    // ─── Class-wide results (teacher/admin) ───────────────────────────────────

    @Transactional(readOnly = true)
    public List<ClassStudentResultDTO> getClassResults(String className, Long examConfigId) {
        List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigId(examConfigId);
        List<Student> students = studentService.getActiveStudentsByClass(className);

        if (students.isEmpty() || entries.isEmpty()) return Collections.emptyList();

        List<Long> entryIds = entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList());
        List<StudentMark> allMarks = studentMarkRepository.findByExamSubjectEntryIdIn(entryIds);

        // studentId → (entryId → marksObtained)
        Map<String, Map<Long, Double>> markMap = new HashMap<>();
        for (StudentMark m : allMarks) {
            markMap.computeIfAbsent(m.getStudentId(), k -> new HashMap<>())
                    .put(m.getExamSubjectEntryId(), m.getMarksObtained());
        }

        Map<String, Double> totalByStudent = computeTotalsByStudent(allMarks);

        List<ClassStudentResultDTO> results = new ArrayList<>();
        for (Student student : students) {
            Map<Long, Double> sMarks = markMap.getOrDefault(student.getStudentId(), Collections.emptyMap());

            List<ClassStudentResultDTO.SubjectMarkDTO> subjectMarks = entries.stream()
                    .map(e -> new ClassStudentResultDTO.SubjectMarkDTO(
                            e.getSubjectName(), e.getMaxMarks(), e.getExamDate(), sMarks.get(e.getId())))
                    .collect(Collectors.toList());

            double total = subjectMarks.stream()
                    .filter(s -> s.getMarksObtained() != null)
                    .mapToDouble(ClassStudentResultDTO.SubjectMarkDTO::getMarksObtained)
                    .sum();
            double maxTotal = entries.stream().mapToInt(ExamSubjectEntry::getMaxMarks).sum();
            double pct = maxTotal > 0 ? round2(total / maxTotal * 100) : 0.0;
            int rank = computeOverallRank(student.getStudentId(), totalByStudent);

            results.add(new ClassStudentResultDTO(
                    student.getStudentId(), student.getName(),
                    subjectMarks, total, maxTotal, pct, rank));
        }

        results.sort(Comparator.comparingInt(ClassStudentResultDTO::getRank));
        return results;
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private List<Student> resolveStudentsForSubject(String className, String subjectName) {
        List<Student> all = studentService.getActiveStudentsByClass(className);
        if (UPPER_CLASSES.contains(className)) {
            return all.stream()
                    .filter(s -> studentHasSubject(s.getStudentId(), subjectName))
                    .collect(Collectors.toList());
        }
        return all;
    }

    /**
     * Returns true if the class 11/12 student has the given subject
     * (either as a stream core subject or their chosen optional subject).
     */
    private boolean studentHasSubject(String studentId, String subjectName) {
        Optional<StudentStreamSelection> selOpt = studentStreamSelectionRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId());
        if (selOpt.isEmpty()) return false;

        StudentStreamSelection sel = selOpt.get();

        boolean isCore = streamCoreSubjectRepository.findByStreamIdAndSchoolId(sel.getStreamId(), securityUtil.getSchoolId())
                .stream()
                .anyMatch(s -> s.getSubjectName().equalsIgnoreCase(subjectName));
        if (isCore) return true;

        if (sel.getOptionalSubjectId() != null) {
            return optionalSubjectRepository.findById(sel.getOptionalSubjectId())
                    .map(os -> os.getSubjectName().equalsIgnoreCase(subjectName))
                    .orElse(false);
        }
        return false;
    }

    private void validateMarkEntry(MarkEntryRequest req) {
        if (req.getStudentId() == null || req.getStudentId().isBlank()) {
            throw new IllegalArgumentException("studentId is required.");
        }
        if (req.getExamSubjectEntryId() == null) {
            throw new IllegalArgumentException("examSubjectEntryId is required.");
        }
        ExamSubjectEntry entry = examSubjectEntryRepository.findById(req.getExamSubjectEntryId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "ExamSubjectEntry not found: " + req.getExamSubjectEntryId()));
        if (req.getMarksObtained() == null) {
            throw new IllegalArgumentException("marksObtained is required.");
        }
        if (req.getMarksObtained() < 0 || req.getMarksObtained() > entry.getMaxMarks()) {
            throw new IllegalArgumentException(
                    "marksObtained must be between 0 and " + entry.getMaxMarks()
                            + " (maxMarks for this subject).");
        }
    }

    /**
     * Sums marks per student across all provided marks (used for overall rank computation).
     * Students with no marks entered are absent from the map.
     */
    private Map<String, Double> computeTotalsByStudent(List<StudentMark> marks) {
        Map<String, Double> totals = new HashMap<>();
        for (StudentMark m : marks) {
            if (m.getMarksObtained() != null) {
                totals.merge(m.getStudentId(), m.getMarksObtained(), Double::sum);
            }
        }
        return totals;
    }

    /**
     * Standard competition rank: 1 + count of students who scored strictly more.
     * Returns 0 if marksObtained is null (not ranked).
     */
    private int computeRank(Double marksObtained, List<StudentMark> allMarks) {
        if (marksObtained == null) return 0;
        long higher = allMarks.stream()
                .filter(m -> m.getMarksObtained() != null && m.getMarksObtained() > marksObtained)
                .count();
        return (int) higher + 1;
    }

    /**
     * Overall rank within an exam based on total marks.
     * Returns 0 if the student has no total (no marks entered).
     */
    private int computeOverallRank(String studentId, Map<String, Double> totalByStudent) {
        Double studentTotal = totalByStudent.get(studentId);
        if (studentTotal == null) return 0;
        long higher = totalByStudent.values().stream().filter(t -> t > studentTotal).count();
        return (int) higher + 1;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
