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

    @Autowired private StudentMarkRepository studentMarkRepository;
    @Autowired private ExamSubjectEntryRepository examSubjectEntryRepository;
    @Autowired private ExamConfigRepository examConfigRepository;
    @Autowired private StudentStreamSelectionRepository studentStreamSelectionRepository;
    @Autowired private StreamCoreSubjectRepository streamCoreSubjectRepository;
    @Autowired private OptionalSubjectRepository optionalSubjectRepository;
    @Autowired private ClassSubjectRepository classSubjectRepository;
    @Autowired private StudentElectiveEnrollmentRepository studentElectiveEnrollmentRepository;
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
    public List<StudentSubjectMarkDTO> getStudentsForSubjectEntry(Long examSubjectEntryId, Long sectionId) {
        ExamSubjectEntry entry = examSubjectEntryRepository.findByIdAndSchoolId(examSubjectEntryId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException(
                        "ExamSubjectEntry not found: " + examSubjectEntryId));
        ExamConfig exam = examConfigRepository.findById(entry.getExamConfigId())
                .orElseThrow(() -> new NoSuchElementException(
                        "ExamConfig not found for entry " + examSubjectEntryId));

        List<Student> students = resolveStudentsForSubject(exam.getClassName(), entry.getSubjectName(), sectionId);

        Set<String> studentIds = students.stream()
                .map(Student::getStudentId).collect(Collectors.toSet());

        Map<String, Double> marksByStudent = studentMarkRepository
                .findByExamSubjectEntryIdAndSchoolId(examSubjectEntryId, securityUtil.getSchoolId())
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
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentService.getStudent(studentId)
                .filter(s -> schoolId.equals(s.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigIdAndSchoolId(examConfigId, schoolId);

        // Check if student has a stream selection — if so, filter by stream subjects
        Set<String> studentStreamSubjects = loadStudentSubjectSet(studentId);
        if (!studentStreamSubjects.isEmpty()) {
            entries = entries.stream()
                    .filter(e -> studentStreamSubjects.contains(e.getSubjectName().toLowerCase()))
                    .collect(Collectors.toList());
        } else {
            // No stream: filter out elective subjects the student isn't enrolled in
            List<ClassSubject> electivesInClass = classSubjectRepository
                    .findByClassNameAndOptionalTrueAndSchoolId(student.getClassName(), schoolId);
            if (!electivesInClass.isEmpty()) {
                Set<String> electiveNames = electivesInClass.stream()
                        .map(ClassSubject::getSubjectName).collect(Collectors.toSet());
                Set<String> enrolledSubjects = studentElectiveEnrollmentRepository
                        .findByStudentIdAndSchoolId(studentId, schoolId)
                        .stream()
                        .map(StudentElectiveEnrollment::getSubjectName)
                        .collect(Collectors.toSet());
                entries = entries.stream()
                        .filter(e -> !electiveNames.contains(e.getSubjectName())
                                || enrolledSubjects.contains(e.getSubjectName()))
                        .collect(Collectors.toList());
            }
        }

        List<Long> entryIds = entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList());

        Map<Long, Double> markByEntryId = studentMarkRepository
                .findByStudentIdAndExamSubjectEntryIdInAndSchoolId(studentId, entryIds, schoolId)
                .stream()
                .collect(Collectors.toMap(StudentMark::getExamSubjectEntryId, StudentMark::getMarksObtained));

        return entries.stream()
                .map(e -> new StudentExamSubjectDTO(
                        e.getId(), e.getSubjectName(), e.getMaxMarks(), e.getExamDate(),
                        markByEntryId.get(e.getId())))
                .collect(Collectors.toList());
    }

    // ─── Bulk mark save (upsert) ──────────────────────────────────────────────

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
                        .findByStudentIdAndExamSubjectEntryIdAndSchoolId(req.getStudentId(), req.getExamSubjectEntryId(), securityUtil.getSchoolId());

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
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentService.getStudent(studentId)
                .filter(s -> schoolId.equals(s.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        List<ExamConfig> exams = (session != null && !session.isBlank())
                ? examConfigRepository.findBySessionAndClassNameAndSchoolId(session, student.getClassName(), schoolId)
                : examConfigRepository.findByClassNameAndSchoolId(student.getClassName(), schoolId);

        // Load stream subject set once — works for any class with stream selections
        Set<String> studentSubjects = loadStudentSubjectSet(studentId);
        boolean isStreamStudent = !studentSubjects.isEmpty();

        // For non-stream students: load elective enrollments once
        Set<String> electiveNames = Set.of();
        Set<String> enrolledElectives = Set.of();
        if (!isStreamStudent) {
            List<ClassSubject> classElectives = classSubjectRepository
                    .findByClassNameAndOptionalTrueAndSchoolId(student.getClassName(), schoolId);
            if (!classElectives.isEmpty()) {
                electiveNames = classElectives.stream()
                        .map(ClassSubject::getSubjectName).collect(Collectors.toSet());
                enrolledElectives = studentElectiveEnrollmentRepository
                        .findByStudentIdAndSchoolId(studentId, schoolId)
                        .stream()
                        .map(StudentElectiveEnrollment::getSubjectName)
                        .collect(Collectors.toSet());
            }
        }
        final Set<String> finalElectiveNames = electiveNames;
        final Set<String> finalEnrolledElectives = enrolledElectives;

        List<ExamResultDTO> results = new ArrayList<>();

        for (ExamConfig exam : exams) {
            List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigIdAndSchoolId(exam.getId(), schoolId);
            if (entries.isEmpty()) continue;

            // Filter to the student's own subjects
            if (isStreamStudent) {
                // Stream-based: only subjects in the student's stream
                entries = entries.stream()
                        .filter(e -> studentSubjects.contains(e.getSubjectName().toLowerCase()))
                        .collect(Collectors.toList());
            } else if (!finalElectiveNames.isEmpty()) {
                // Elective-based: exclude elective subjects the student didn't choose
                entries = entries.stream()
                        .filter(e -> !finalElectiveNames.contains(e.getSubjectName())
                                || finalEnrolledElectives.contains(e.getSubjectName()))
                        .collect(Collectors.toList());
            }
            if (entries.isEmpty()) continue;

            List<Long> entryIds = entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList());

            // Student's marks
            Map<Long, Double> studentMarkMap = studentMarkRepository
                    .findByStudentIdAndExamSubjectEntryIdInAndSchoolId(studentId, entryIds, schoolId)
                    .stream()
                    .collect(Collectors.toMap(StudentMark::getExamSubjectEntryId, StudentMark::getMarksObtained));

            // All marks for this exam (for class average + per-subject rank)
            List<StudentMark> allMarks = studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(entryIds, schoolId);
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
    public List<ClassStudentResultDTO> getClassResults(String className, Long examConfigId, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigIdAndSchoolId(examConfigId, schoolId);
        List<Student> students = (sectionId != null)
                ? studentService.getActiveStudentsByClassAndSection(className, sectionId)
                : studentService.getActiveStudentsByClass(className);

        if (students.isEmpty() || entries.isEmpty()) return Collections.emptyList();

        // Determine per-student subject filtering strategy:
        // 1. Check if any students have stream selections (stream-based classes like 9-12)
        // 2. Otherwise check for elective enrollments (classes 1-10 with optional subjects)
        Map<String, Set<String>> streamSubjectsByStudent = batchLoadStudentSubjectSets(students);
        boolean hasStreamStudents = streamSubjectsByStudent.values().stream().anyMatch(s -> !s.isEmpty());

        // Load elective info for the class (used when no stream selections exist)
        List<ClassSubject> classElectives = hasStreamStudents
                ? List.of()
                : classSubjectRepository.findByClassNameAndOptionalTrueAndSchoolId(className, schoolId);
        Set<String> electiveNames = classElectives.stream()
                .map(ClassSubject::getSubjectName).collect(Collectors.toSet());

        Map<String, Set<String>> enrollmentsByStudent = new HashMap<>();
        if (!electiveNames.isEmpty()) {
            studentElectiveEnrollmentRepository.findByClassNameAndSchoolId(className, schoolId)
                    .forEach(e -> enrollmentsByStudent
                            .computeIfAbsent(e.getStudentId(), k -> new HashSet<>())
                            .add(e.getSubjectName()));
        }

        List<Long> entryIds = entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList());
        List<StudentMark> allMarks = studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(entryIds, schoolId);

        // studentId → (entryId → marksObtained)
        Map<String, Map<Long, Double>> markMap = new HashMap<>();
        for (StudentMark m : allMarks) {
            markMap.computeIfAbsent(m.getStudentId(), k -> new HashMap<>())
                    .put(m.getExamSubjectEntryId(), m.getMarksObtained());
        }

        // First pass: compute percentage per student for fair ranking.
        Map<String, Double> percentageByStudent = new HashMap<>();
        for (Student student : students) {
            Map<Long, Double> sMarks = markMap.getOrDefault(student.getStudentId(), Collections.emptyMap());
            List<ExamSubjectEntry> studentEntries = filterEntriesForStudent(
                    entries, student, hasStreamStudents, streamSubjectsByStudent, electiveNames, enrollmentsByStudent);

            boolean hasMarks = studentEntries.stream().anyMatch(e -> sMarks.containsKey(e.getId()));
            if (hasMarks) {
                double total = studentEntries.stream()
                        .map(e -> sMarks.get(e.getId()))
                        .filter(Objects::nonNull)
                        .mapToDouble(Double::doubleValue).sum();
                double maxTotal = studentEntries.stream().mapToInt(ExamSubjectEntry::getMaxMarks).sum();
                if (maxTotal > 0) {
                    percentageByStudent.put(student.getStudentId(), round2(total / maxTotal * 100));
                }
            }
        }

        // Second pass: build results with percentage-based ranking
        List<ClassStudentResultDTO> results = new ArrayList<>();
        for (Student student : students) {
            Map<Long, Double> sMarks = markMap.getOrDefault(student.getStudentId(), Collections.emptyMap());
            List<ExamSubjectEntry> studentEntries = filterEntriesForStudent(
                    entries, student, hasStreamStudents, streamSubjectsByStudent, electiveNames, enrollmentsByStudent);

            List<ClassStudentResultDTO.SubjectMarkDTO> subjectMarks = studentEntries.stream()
                    .map(e -> new ClassStudentResultDTO.SubjectMarkDTO(
                            e.getSubjectName(), e.getMaxMarks(), e.getExamDate(), sMarks.get(e.getId())))
                    .collect(Collectors.toList());

            double total = subjectMarks.stream()
                    .filter(s -> s.getMarksObtained() != null)
                    .mapToDouble(ClassStudentResultDTO.SubjectMarkDTO::getMarksObtained)
                    .sum();
            double maxTotal = studentEntries.stream().mapToInt(ExamSubjectEntry::getMaxMarks).sum();
            double pct = maxTotal > 0 ? round2(total / maxTotal * 100) : 0.0;
            int rank = computeOverallRank(student.getStudentId(), percentageByStudent);

            results.add(new ClassStudentResultDTO(
                    student.getStudentId(), student.getName(),
                    subjectMarks, total, maxTotal, pct, rank));
        }

        results.sort(Comparator.comparingInt(ClassStudentResultDTO::getRank));
        return results;
    }

    /**
     * Filters exam subject entries to only those applicable to a specific student.
     * For stream-based classes: only subjects in the student's stream.
     * For elective-based classes: all non-elective subjects + enrolled electives.
     * For classes with neither: all entries.
     */
    private List<ExamSubjectEntry> filterEntriesForStudent(
            List<ExamSubjectEntry> entries, Student student,
            boolean hasStreamStudents, Map<String, Set<String>> streamSubjectsByStudent,
            Set<String> electiveNames, Map<String, Set<String>> enrollmentsByStudent) {

        if (hasStreamStudents) {
            Set<String> studentSubjects = streamSubjectsByStudent.getOrDefault(student.getStudentId(), Set.of());
            if (studentSubjects.isEmpty()) return entries; // no stream selection — show all
            return entries.stream()
                    .filter(e -> studentSubjects.contains(e.getSubjectName().toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (!electiveNames.isEmpty()) {
            Set<String> enrolled = enrollmentsByStudent.getOrDefault(student.getStudentId(), Set.of());
            return entries.stream()
                    .filter(e -> !electiveNames.contains(e.getSubjectName())
                            || enrolled.contains(e.getSubjectName()))
                    .collect(Collectors.toList());
        }

        return entries;
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private List<Student> resolveStudentsForSubject(String className, String subjectName, Long sectionId) {
        // Fetch students for the class — filtered by section if provided
        List<Student> all = (sectionId != null)
                ? studentService.getActiveStudentsByClassAndSection(className, sectionId)
                : studentService.getActiveStudentsByClass(className);

        // Check if any students have stream selections — filter by stream subjects
        Map<String, Set<String>> subjectSetByStudent = batchLoadStudentSubjectSets(all);
        boolean hasStreamStudents = subjectSetByStudent.values().stream().anyMatch(s -> !s.isEmpty());
        if (hasStreamStudents) {
            String subjectLower = subjectName.toLowerCase();
            return all.stream()
                    .filter(s -> {
                        Set<String> subjects = subjectSetByStudent.getOrDefault(s.getStudentId(), Set.of());
                        return subjects.isEmpty() || subjects.contains(subjectLower); // no stream = show all
                    })
                    .collect(Collectors.toList());
        }

        // No stream students: check if this subject is an elective
        Long schoolId = securityUtil.getSchoolId();
        boolean isElective = classSubjectRepository
                .existsByClassNameAndSubjectNameAndOptionalTrueAndSchoolId(className, subjectName, schoolId);
        if (!isElective) return all;

        // Return only students enrolled in this elective
        Set<String> enrolledIds = studentElectiveEnrollmentRepository
                .findByClassNameAndSubjectNameAndSchoolId(className, subjectName, schoolId)
                .stream()
                .map(StudentElectiveEnrollment::getStudentId)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(s -> enrolledIds.contains(s.getStudentId()))
                .collect(Collectors.toList());
    }

    /**
     * Loads the lowercase subject names for a single class-11/12 student.
     * Makes exactly 2–3 DB calls once per invocation — not per subject check.
     */
    private Set<String> loadStudentSubjectSet(String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        Optional<StudentStreamSelection> selOpt =
                studentStreamSelectionRepository.findByStudentIdAndSchoolId(studentId, schoolId);
        if (selOpt.isEmpty()) return Set.of();

        StudentStreamSelection sel = selOpt.get();
        Set<String> subjects = new HashSet<>();

        streamCoreSubjectRepository.findByStreamIdAndSchoolId(sel.getStreamId(), schoolId)
                .forEach(s -> subjects.add(s.getSubjectName().toLowerCase()));

        if (sel.getOptionalSubjectId() != null) {
            optionalSubjectRepository.findById(sel.getOptionalSubjectId())
                    .ifPresent(os -> subjects.add(os.getSubjectName().toLowerCase()));
        }
        return subjects;
    }

    /**
     * Batch-loads stream selections and subjects for a list of students.
     * Makes ~3 DB calls regardless of student count.
     * Returns: studentId → set of lowercase subject names the student takes.
     */
    private Map<String, Set<String>> batchLoadStudentSubjectSets(List<Student> students) {
        if (students.isEmpty()) return Map.of();
        Long schoolId = securityUtil.getSchoolId();

        List<String> studentIds = students.stream()
                .map(Student::getStudentId).collect(Collectors.toList());

        // 1 DB call: all stream selections for these students
        List<StudentStreamSelection> selections =
                studentStreamSelectionRepository.findByStudentIdInAndSchoolId(studentIds, schoolId);

        Map<String, StudentStreamSelection> selByStudent = selections.stream()
                .collect(Collectors.toMap(StudentStreamSelection::getStudentId, s -> s));

        Set<Long> streamIds = selections.stream()
                .map(StudentStreamSelection::getStreamId).collect(Collectors.toSet());
        Set<Long> optionalIds = selections.stream()
                .filter(s -> s.getOptionalSubjectId() != null)
                .map(StudentStreamSelection::getOptionalSubjectId)
                .collect(Collectors.toSet());

        // 1 DB call: all core subjects for all relevant streams
        Map<Long, Set<String>> coreByStream = new HashMap<>();
        if (!streamIds.isEmpty()) {
            streamCoreSubjectRepository.findByStreamIdInAndSchoolId(streamIds, schoolId)
                    .forEach(s -> coreByStream
                            .computeIfAbsent(s.getStreamId(), k -> new HashSet<>())
                            .add(s.getSubjectName().toLowerCase()));
        }

        // 1 DB call: all optional subjects
        Map<Long, String> optSubjectById = new HashMap<>();
        if (!optionalIds.isEmpty()) {
            optionalSubjectRepository.findAllById(optionalIds)
                    .forEach(os -> optSubjectById.put(os.getId(), os.getSubjectName().toLowerCase()));
        }

        // Assemble per-student subject sets in memory
        Map<String, Set<String>> result = new HashMap<>();
        for (Student student : students) {
            StudentStreamSelection sel = selByStudent.get(student.getStudentId());
            if (sel == null) {
                result.put(student.getStudentId(), Set.of());
                continue;
            }
            Set<String> subjects = new HashSet<>(coreByStream.getOrDefault(sel.getStreamId(), Set.of()));
            if (sel.getOptionalSubjectId() != null) {
                String optName = optSubjectById.get(sel.getOptionalSubjectId());
                if (optName != null) subjects.add(optName);
            }
            result.put(student.getStudentId(), subjects);
        }
        return result;
    }

    private void validateMarkEntry(MarkEntryRequest req) {
        if (req.getStudentId() == null || req.getStudentId().isBlank()) {
            throw new IllegalArgumentException("studentId is required.");
        }
        if (req.getExamSubjectEntryId() == null) {
            throw new IllegalArgumentException("examSubjectEntryId is required.");
        }
        ExamSubjectEntry entry = examSubjectEntryRepository.findByIdAndSchoolId(req.getExamSubjectEntryId(), securityUtil.getSchoolId())
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
