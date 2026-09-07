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

import java.time.LocalDate;
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
    @Autowired private StudentRepository studentRepository;
    @Autowired private StudentTemporalMembershipResolver temporalMembershipResolver;
    @Autowired private StudentEnrollmentRepository studentEnrollmentRepository;
    @Autowired private AcademicSessionRepository academicSessionRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;

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

        List<Student> students = resolveStudentsForSubject(exam, entry, sectionId);

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
        // Existence/tenant check only — studentService.getStudent's CURRENT className is never
        // read for the elective decision below; the exam's OWN className is used instead (see
        // ExamConfig fetch), since this examConfigId can be historical (from before a promotion).
        studentService.getStudent(studentId)
                .filter(s -> schoolId.equals(s.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        ExamConfig exam = examConfigRepository.findById(examConfigId)
                .filter(e -> schoolId.equals(e.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("ExamConfig not found: " + examConfigId));

        List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigIdAndSchoolId(examConfigId, schoolId);

        // Check if student has a stream selection — if so, filter by stream subjects
        Set<String> studentStreamSubjects = loadStudentSubjectSet(studentId);
        if (!studentStreamSubjects.isEmpty()) {
            entries = entries.stream()
                    .filter(e -> studentStreamSubjects.contains(e.getSubjectName().toLowerCase()))
                    .collect(Collectors.toList());
        } else {
            // No stream: filter out elective subjects the student isn't enrolled in. Uses
            // exam.getClassName() — the exam's own, historically-correct class — never the
            // student's current class, which can differ after a promotion that happened since
            // this exam was held.
            List<ClassSubject> electivesInClass = classSubjectRepository
                    .findByClassNameAndOptionalTrueAndSchoolId(exam.getClassName(), schoolId);
            if (!electivesInClass.isEmpty()) {
                Set<String> electiveNames = electivesInClass.stream()
                        .map(ClassSubject::getSubjectName).collect(Collectors.toSet());
                // StudentElectiveEnrollment is itself recorded per-class — filter to the exam's
                // class so a same-named elective chosen in a different class the student was
                // previously (or later) in never leaks into this exam's subject list.
                Set<String> enrolledSubjects = studentElectiveEnrollmentRepository
                        .findByStudentIdAndSchoolId(studentId, schoolId)
                        .stream()
                        .filter(en -> exam.getClassName().equals(en.getClassName()))
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

        List<ExamConfig> exams;
        if (session != null && !session.isBlank()) {
            // Never student.getClassName() here — that's the student's CURRENT class, and after
            // a promotion it silently returns nothing for a past session's exams (ExamConfig is
            // keyed by className+session, and a promoted student's current class never matches
            // the class they were actually in during that old session). The correct historical
            // anchor is: (a) the student's OWN StudentMark rows for exams in this session — an
            // existing mark is always the strongest evidence and is never dropped even if it
            // disagrees with enrollment — UNIONED with (b) E6B realized-enrollment class(es) for
            // this session, so a promoted student can discover a configured-but-not-yet-marked
            // prior exam too. Falls back to the current class only when E6B classifies the
            // context as genuinely legacy (no enrollment data at all for this student/session).
            Set<String> markDerivedClassNames = resolveMarkDerivedClassNamesForSession(studentId, session, schoolId);
            EnrollmentSessionClasses enrollment = resolveEnrollmentClassNamesForSession(studentId, session, schoolId);
            if (enrollment.conflict()) {
                log.error("Enrollment conflict resolving historical exam context for student {} session {}: {} — " +
                        "using mark evidence only; existing marks remain factual.",
                        studentId, session, enrollment.conflictReason());
            }
            Set<String> combinedClassNames = new LinkedHashSet<>(markDerivedClassNames);
            if (!enrollment.conflict()) combinedClassNames.addAll(enrollment.classNames());
            // True only when there is no mark evidence AND no real enrollment segment for this
            // session — the resulting class name is the live-current-class safety net (legacy),
            // not a genuine enrollment discovery, so it must NOT be subjected to the per-subject-
            // date enrollment check below (there are no segments to check it against).
            boolean legacyFallbackOnly = combinedClassNames.isEmpty() && enrollment.legacyFallbackPermitted();
            if (combinedClassNames.isEmpty()) {
                combinedClassNames = legacyFallbackOnly ? Set.of(student.getClassName()) : Set.of();
            }

            exams = new ArrayList<>();
            Set<Long> seenExamIds = new HashSet<>();
            for (String cn : combinedClassNames) {
                for (ExamConfig candidate : examConfigRepository.findBySessionAndClassNameAndSchoolId(session, cn, schoolId)) {
                    if (!seenExamIds.add(candidate.getId())) continue;
                    if (markDerivedClassNames.contains(cn) || legacyFallbackOnly) {
                        exams.add(candidate); // existing-mark authority, or safe legacy fallback
                        continue;
                    }
                    // Discovered via enrollment only (no mark yet) — for dated subject entries,
                    // resolve membership per the relevant subject date rather than once for the
                    // whole session; an exam with no date evidence uses session-level enrollment
                    // as sufficient (never invent a date).
                    List<ExamSubjectEntry> candidateEntries =
                            examSubjectEntryRepository.findByExamConfigIdAndSchoolId(candidate.getId(), schoolId);
                    if (examMatchesEnrollmentSegments(candidateEntries, cn, enrollment.segments())) {
                        exams.add(candidate);
                    } else {
                        log.info("Skipping enrollment-discovered exam {} ({}/{}) for student {}: no subject date " +
                                "falls within the student's realized enrollment segment for class {}.",
                                candidate.getId(), session, cn, studentId, cn);
                    }
                }
            }
        } else {
            // No session filter: preserve the exact existing baseline (every exam ever
            // configured for the student's CURRENT class, across every session) as the floor,
            // then add exams for any OTHER class the student was realized-enrolled in at some
            // point (so a promotion never erases prior-session results), plus any exam the
            // student has an existing mark for regardless of class (mark authority — never
            // dropped). The student's current class must never erase old results.
            List<ExamConfig> currentClassExams = examConfigRepository.findByClassNameAndSchoolId(student.getClassName(), schoolId);
            exams = new ArrayList<>(currentClassExams);
            Set<Long> seenExamIds = currentClassExams.stream().map(ExamConfig::getId).collect(Collectors.toCollection(HashSet::new));

            for (SessionClassContext ctx : resolveHistoricalSessionClassContexts(studentId, schoolId)) {
                if (student.getClassName().equals(ctx.className())) continue; // already covered above
                for (ExamConfig candidate : examConfigRepository.findBySessionAndClassNameAndSchoolId(ctx.session(), ctx.className(), schoolId)) {
                    if (seenExamIds.add(candidate.getId())) exams.add(candidate);
                }
            }
            for (ExamConfig candidate : examConfigsWithExistingMarks(studentId, schoolId)) {
                if (seenExamIds.add(candidate.getId())) exams.add(candidate);
            }
        }

        // Load stream subject set once — works for any class with stream selections, and is
        // itself student-scoped with no class dimension (a stream selection is a single choice
        // for the whole 11-12 duration — see StudentStreamSelection.studentId being unique),
        // so it needs no historical-class handling.
        Set<String> studentSubjects = loadStudentSubjectSet(studentId);
        boolean isStreamStudent = !studentSubjects.isEmpty();

        // For non-stream students: pre-fetch the student's own elective-enrollment rows once
        // (cheap — one small list per student), filtered per-exam below by THAT exam's own
        // class. StudentElectiveEnrollment is itself recorded per-class (its unique constraint
        // includes class_name), so a student's Class-9 elective choice and a later Class-10
        // choice are already distinct rows — the bug was never re-deriving this per exam, it
        // was using the student's CURRENT class to decide "what electives even exist" at all.
        List<StudentElectiveEnrollment> ownElectiveEnrollments = isStreamStudent
                ? List.of()
                : studentElectiveEnrollmentRepository.findByStudentIdAndSchoolId(studentId, schoolId);
        Map<String, List<ClassSubject>> electivesByClassNameCache = new HashMap<>();

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
            } else {
                // The exam's OWN class — never the student's current one — is the correct
                // historical anchor for "what electives existed for this exam."
                List<ClassSubject> classElectives = electivesByClassNameCache.computeIfAbsent(
                        exam.getClassName(),
                        cn -> classSubjectRepository.findByClassNameAndOptionalTrueAndSchoolId(cn, schoolId));
                if (!classElectives.isEmpty()) {
                    Set<String> electiveNames = classElectives.stream()
                            .map(ClassSubject::getSubjectName).collect(Collectors.toSet());
                    Set<String> enrolledElectives = ownElectiveEnrollments.stream()
                            .filter(en -> exam.getClassName().equals(en.getClassName()))
                            .map(StudentElectiveEnrollment::getSubjectName)
                            .collect(Collectors.toSet());
                    entries = entries.stream()
                            .filter(e -> !electiveNames.contains(e.getSubjectName())
                                    || enrolledElectives.contains(e.getSubjectName()))
                            .collect(Collectors.toList());
                }
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
        List<Student> liveStudents = (sectionId != null)
                ? studentService.getActiveStudentsByClassAndSection(className, sectionId)
                : studentService.getActiveStudentsByClass(className);
        Map<String, Student> roster = new LinkedHashMap<>();
        liveStudents.forEach(s -> roster.put(s.getStudentId(), s));
        // Enrollment-authoritative augmentation (E6D): a student realized-enrolled in this
        // class(+section) for this exam's own dates remains part of the class results even if
        // since promoted, transferred, or withdrawn.
        examConfigRepository.findById(examConfigId)
                .filter(e -> schoolId.equals(e.getSchoolId()))
                .ifPresent(exam -> augmentRosterWithEnrollment(roster, schoolId, exam, className, sectionId, entries));
        List<Student> students = new ArrayList<>(roster.values());

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

    // ─── Consolidated exam performance (teacher/admin) ─────────────────────────
    // Collapses the "resolve latest/named exam, then fetch+aggregate results"
    // round trip that used to happen client-side (one HTTP call per step, times
    // one per class for the school-wide view) into single in-process calls.

    @Transactional(readOnly = true)
    public List<ExamConfig> getExamsForClass(String session, String className) {
        return examConfigRepository.findBySessionAndClassNameAndSchoolId(session, className, securityUtil.getSchoolId());
    }

    /** Named exam if given (case-insensitive match), else the most recently created one (highest id). */
    public Optional<ExamConfig> resolveExam(List<ExamConfig> exams, String examName) {
        if (examName != null && !examName.isBlank()) {
            return exams.stream().filter(e -> e.getExamName().equalsIgnoreCase(examName)).findFirst();
        }
        return exams.stream().max(Comparator.comparing(ExamConfig::getId));
    }

    /**
     * Aggregates one class's one exam into class average, ranked student list, and
     * subject averages. A rank of 0 (see computeOverallRank) means no mark was
     * entered for that student — those students are excluded from the ranking and
     * averages and listed separately in studentsWithNoMarksEntered instead, so they
     * don't silently drag the class average down as if they'd scored zero.
     */
    @Transactional(readOnly = true)
    public ClassExamPerformanceDTO computeClassExamPerformance(String className, ExamConfig exam) {
        return computeClassExamPerformance(className, exam, null);
    }

    public ClassExamPerformanceDTO computeClassExamPerformance(String className, ExamConfig exam, Long sectionId) {
        List<ClassStudentResultDTO> results = getClassResults(className, exam.getId(), sectionId);

        List<ClassStudentResultDTO> scored = results.stream()
                .filter(r -> r.getRank() != null && r.getRank() > 0)
                .collect(Collectors.toList());
        List<String> noMarksEntered = results.stream()
                .filter(r -> r.getRank() == null || r.getRank() == 0)
                .map(ClassStudentResultDTO::getStudentName)
                .collect(Collectors.toList());

        List<ClassExamPerformanceDTO.StudentScoreDTO> ranked = scored.stream()
                .sorted(Comparator.comparingDouble(ClassStudentResultDTO::getPercentage).reversed())
                .map(r -> new ClassExamPerformanceDTO.StudentScoreDTO(r.getStudentName(), r.getPercentage(), r.getRank()))
                .collect(Collectors.toList());

        Double classAvg = scored.isEmpty() ? null
                : round2(scored.stream().mapToDouble(ClassStudentResultDTO::getPercentage).average().orElse(0));

        Map<String, List<Double>> subjectScores = new LinkedHashMap<>();
        for (ClassStudentResultDTO r : scored) {
            for (ClassStudentResultDTO.SubjectMarkDTO s : r.getSubjects()) {
                if (s.getMarksObtained() != null && s.getMaxMarks() != null && s.getMaxMarks() > 0) {
                    subjectScores.computeIfAbsent(s.getSubjectName(), k -> new ArrayList<>())
                            .add(s.getMarksObtained() / s.getMaxMarks() * 100);
                }
            }
        }
        Map<String, Double> subjectAverages = new LinkedHashMap<>();
        subjectScores.forEach((subject, scoresList) ->
                subjectAverages.put(subject, round2(scoresList.stream().mapToDouble(Double::doubleValue).average().orElse(0))));

        return new ClassExamPerformanceDTO(className, exam.getExamName(), classAvg, ranked, noMarksEntered, subjectAverages);
    }

    /** Every active class's own latest exam performance, in one call — see ClassExamPerformanceDTO. */
    @Transactional(readOnly = true)
    public SchoolPerformanceSummaryDTO getSchoolPerformanceSummary(String session) {
        Long schoolId = securityUtil.getSchoolId();
        Set<String> classNames = new LinkedHashSet<>(studentRepository.findDistinctActiveClassNamesBySchoolId(schoolId));
        // Enrollment-authoritative augmentation (E6D): a class with realized enrollment during
        // this session must appear even if it currently has zero ACTIVE students (e.g. every
        // student since promoted/exited) — the school-wide summary must not depend solely on
        // who is active today.
        academicSessionRepository.findBySchoolIdAndLabel(schoolId, session).ifPresent(as -> {
            for (StudentEnrollment e : studentEnrollmentRepository.findRealizedByAcademicSessionOverlappingRange(
                    schoolId, as.getId(), as.getStartDate(), as.getEndDate())) {
                if (e.getClassNameSnapshot() != null) classNames.add(e.getClassNameSnapshot());
            }
        });

        List<ClassExamPerformanceDTO> classResults = new ArrayList<>();
        List<String> noExamConfigured = new ArrayList<>();
        List<String> examButNoMarks = new ArrayList<>();

        for (String className : classNames) {
            List<ExamConfig> exams = getExamsForClass(session, className);
            if (exams.isEmpty()) {
                noExamConfigured.add(className);
                continue;
            }
            ExamConfig latest = resolveExam(exams, null).orElseThrow();
            ClassExamPerformanceDTO dto = computeClassExamPerformance(className, latest);
            if (dto.getStudentsRanked().isEmpty()) {
                examButNoMarks.add(className);
                continue;
            }
            classResults.add(dto);
        }

        return new SchoolPerformanceSummaryDTO(session, classResults, noExamConfigured, examButNoMarks);
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

    /** Which class(es) a student's own StudentMark rows show for exams in the given session —
     *  the correct historical anchor, since ExamConfig has no direct student link and the
     *  student's CURRENT className (student.getClassName()) is exactly what breaks after a
     *  promotion: an old session's exams were configured under the OLD class name, which the
     *  student's new current class never matches. No student_enrollment table exists to look
     *  this up structurally, so it's derived deterministically from the marks data that's
     *  already there: every ExamConfig in this session, joined to this student's own marks via
     *  ExamSubjectEntry, tells us which class(es) they actually sat exams under this session.
     *  Falls back to the given current className only when the student has no marks at all yet
     *  in this session (e.g. a brand-new, ungraded exam in the session they're currently in —
     *  the safe, common-case default). More than one distinct class is possible in principle
     *  (a genuine mid-session class change) — every ExamConfig for the session found across
     *  those classes is fetched by the caller, so no exam is dropped, whichever class it's
     *  under.
     *  <p>Public — also shared by ReportCardDataAssembler, which needs the same "what class was
     *  this session actually for" answer to resolve a report card's historical class/publication
     *  lookup, rather than maintaining a second, independent resolver over the same data.
     *  <p>E6D: also unions in any class the student was realized-enrolled in during this session
     *  (StudentTemporalMembershipResolver / E6B) — so a configured-but-not-yet-marked exam is
     *  discoverable purely from enrollment, without waiting for a mark to exist. Existing marks
     *  are never removed by this; enrollment only adds. When there is no mark AND no realized
     *  enrollment for this session, the live current class is used only when E6B classifies the
     *  context as genuinely legacy (pre-adoption / no enrollment data at all) — an authoritative
     *  gap must not be papered over with the live class. */
    @Transactional(readOnly = true)
    public Set<String> resolveHistoricalClassNamesForSession(String studentId, String session, Long schoolId, String fallbackClassName) {
        Set<String> markDerivedClassNames = resolveMarkDerivedClassNamesForSession(studentId, session, schoolId);
        EnrollmentSessionClasses enrollment = resolveEnrollmentClassNamesForSession(studentId, session, schoolId);
        if (enrollment.conflict()) {
            log.error("Enrollment conflict resolving historical class context for student {} session {}: {} — " +
                    "using mark evidence only.", studentId, session, enrollment.conflictReason());
            return markDerivedClassNames.isEmpty() ? Set.of(fallbackClassName) : markDerivedClassNames;
        }
        Set<String> combined = new LinkedHashSet<>(markDerivedClassNames);
        combined.addAll(enrollment.classNames());
        if (!combined.isEmpty()) return combined;
        return enrollment.legacyFallbackPermitted() ? Set.of(fallbackClassName) : Set.of();
    }

    /** The mark-derived half of resolveHistoricalClassNamesForSession — extracted so
     *  getStudentResults can also use it standalone (to distinguish "mark authority" classes
     *  from "enrollment-only, date-checked" classes). See that method's Javadoc for the full
     *  reasoning; unchanged from the original bridge. */
    private Set<String> resolveMarkDerivedClassNamesForSession(String studentId, String session, Long schoolId) {
        List<ExamConfig> sessionExams = examConfigRepository.findBySessionAndSchoolId(session, schoolId);
        if (sessionExams.isEmpty()) return Set.of();
        Map<Long, String> classNameByExamConfigId = sessionExams.stream()
                .collect(Collectors.toMap(ExamConfig::getId, ExamConfig::getClassName, (a, b) -> a));

        List<ExamSubjectEntry> allEntries = examSubjectEntryRepository
                .findByExamConfigIdInAndSchoolId(new ArrayList<>(classNameByExamConfigId.keySet()), schoolId);
        if (allEntries.isEmpty()) return Set.of();
        Map<Long, Long> examConfigIdByEntryId = allEntries.stream()
                .collect(Collectors.toMap(ExamSubjectEntry::getId, ExamSubjectEntry::getExamConfigId, (a, b) -> a));

        List<StudentMark> ownMarks = studentMarkRepository.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(
                studentId, new ArrayList<>(examConfigIdByEntryId.keySet()), schoolId);

        return ownMarks.stream()
                .map(m -> examConfigIdByEntryId.get(m.getExamSubjectEntryId()))
                .filter(Objects::nonNull)
                .map(classNameByExamConfigId::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Realized (ACTIVE/CLOSED) enrollment class names for a student/session, via E6B — never a
     *  second, independent temporal resolver. {@code legacyFallbackPermitted} mirrors E6B's own
     *  classification: true when there's no realized enrollment history at all (safe to fall
     *  back to the live current class), false for an authoritative gap (must not fabricate
     *  membership from the live class). {@code conflict} means E6B detected ambiguous/dirty
     *  enrollment data — fail closed on the enrollment contribution, but existing marks still
     *  stand as their own historical evidence. */
    private record EnrollmentSessionClasses(
            Set<String> classNames, List<StudentTemporalMembershipResolver.Segment> segments,
            boolean legacyFallbackPermitted, boolean conflict, String conflictReason) {}

    private EnrollmentSessionClasses resolveEnrollmentClassNamesForSession(String studentId, String session, Long schoolId) {
        Optional<AcademicSession> sessionOpt = academicSessionRepository.findBySchoolIdAndLabel(schoolId, session);
        if (sessionOpt.isEmpty()) {
            return new EnrollmentSessionClasses(Set.of(), List.of(), true, false, null);
        }
        StudentTemporalMembershipResolver.SessionResolution resolution;
        try {
            resolution = temporalMembershipResolver.realizedEnrollmentSegmentsForSession(
                    schoolId, studentId, sessionOpt.get().getId());
        } catch (RuntimeException e) {
            log.error("Failed to resolve realized enrollment for student {} session {}", studentId, session, e);
            return new EnrollmentSessionClasses(Set.of(), List.of(), true, false, null);
        }
        if (resolution.classification() == StudentTemporalMembershipResolver.CoverageClassification.CONFLICT) {
            return new EnrollmentSessionClasses(Set.of(), List.of(), false, true, resolution.conflictReason());
        }
        Set<String> classNames = resolution.segments().stream()
                .map(StudentTemporalMembershipResolver.Segment::classNameSnapshot)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new EnrollmentSessionClasses(classNames, resolution.segments(), resolution.legacyFallbackPermitted(), false, null);
    }

    /** For an exam discovered purely via enrollment (no mark yet): does at least one of its
     *  subject dates fall within a realized enrollment segment of the matching class? An exam
     *  with no dated subjects can't be checked this way — session-level enrollment in that class
     *  is treated as sufficient (never invent a date). */
    private boolean examMatchesEnrollmentSegments(
            List<ExamSubjectEntry> entries, String className, List<StudentTemporalMembershipResolver.Segment> segments) {
        List<StudentTemporalMembershipResolver.Segment> classSegments = segments.stream()
                .filter(s -> className.equals(s.classNameSnapshot())).toList();
        if (classSegments.isEmpty()) return false;
        List<LocalDate> dates = entries.stream().map(ExamSubjectEntry::getExamDate).filter(Objects::nonNull).toList();
        if (dates.isEmpty()) return true;
        return dates.stream().anyMatch(d -> classSegments.stream().anyMatch(s ->
                !d.isBefore(s.effectiveFrom()) && (s.effectiveUntil() == null || !d.isAfter(s.effectiveUntil()))));
    }

    /** Every distinct (session label, class name) the student was realized-enrolled under,
     *  across all sessions — used by getStudentResults' no-session branch so a promotion never
     *  erases a prior session's results just because no session filter was given. */
    private record SessionClassContext(String session, String className) {}

    private List<SessionClassContext> resolveHistoricalSessionClassContexts(String studentId, Long schoolId) {
        List<StudentEnrollment> rows;
        try {
            rows = studentEnrollmentRepository.findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(schoolId, studentId);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<SessionClassContext> contexts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StudentEnrollment row : rows) {
            if (row.getStatus() != StudentEnrollmentStatus.ACTIVE && row.getStatus() != StudentEnrollmentStatus.CLOSED) continue;
            Optional<AcademicSession> sessionOpt = academicSessionRepository.findByIdAndSchoolId(row.getAcademicSessionId(), schoolId);
            if (sessionOpt.isEmpty() || row.getClassNameSnapshot() == null) continue;
            String key = sessionOpt.get().getLabel() + "\u0000" + row.getClassNameSnapshot();
            if (seen.add(key)) contexts.add(new SessionClassContext(sessionOpt.get().getLabel(), row.getClassNameSnapshot()));
        }
        return contexts;
    }

    /** Every ExamConfig the student has an existing mark for, regardless of class or session —
     *  mark authority: an existing mark must never be dropped from the no-session enumeration. */
    private List<ExamConfig> examConfigsWithExistingMarks(String studentId, Long schoolId) {
        List<StudentMark> marks = studentMarkRepository.findByStudentIdAndSchoolId(studentId, schoolId);
        if (marks.isEmpty()) return List.of();
        Set<Long> entryIds = marks.stream().map(StudentMark::getExamSubjectEntryId).collect(Collectors.toSet());
        Set<Long> examConfigIds = new HashSet<>();
        for (ExamSubjectEntry entry : examSubjectEntryRepository.findAllById(entryIds)) {
            if (schoolId.equals(entry.getSchoolId())) examConfigIds.add(entry.getExamConfigId());
        }
        if (examConfigIds.isEmpty()) return List.of();
        List<ExamConfig> result = new ArrayList<>();
        for (ExamConfig e : examConfigRepository.findAllById(examConfigIds)) {
            if (schoolId.equals(e.getSchoolId())) result.add(e);
        }
        return result;
    }

    /** Enrollment-authoritative roster augmentation (E6D), shared by getClassResults,
     *  getStudentsForSubjectEntry, and any other class-result view: adds any student
     *  realized-enrolled in {@code className}(+{@code sectionId}) for the exam's own subject
     *  date range — even if since promoted, transferred, or withdrawn — to the given live-roster
     *  map (keyed by studentId, mutated in place). No-ops gracefully when the exam's session or
     *  class can't be resolved (leaves the live-only roster untouched, exactly as before E6D). */
    private void augmentRosterWithEnrollment(Map<String, Student> roster, Long schoolId, ExamConfig exam,
                                             String className, Long sectionId, List<ExamSubjectEntry> entries) {
        Optional<AcademicSession> sessionOpt = academicSessionRepository.findBySchoolIdAndLabel(schoolId, exam.getSession());
        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className).map(SchoolClass::getId).orElse(null);
        if (sessionOpt.isEmpty() || classId == null) return;
        AcademicSession session = sessionOpt.get();
        LocalDate from = entries.stream().map(ExamSubjectEntry::getExamDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(session.getStartDate());
        LocalDate to = entries.stream().map(ExamSubjectEntry::getExamDate).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(session.getEndDate());
        if (from == null || to == null || to.isBefore(from)) return;

        List<StudentEnrollment> rows;
        try {
            rows = (sectionId != null)
                    ? studentEnrollmentRepository.findRealizedByAcademicSessionAndClassAndSectionOverlappingRange(
                            schoolId, session.getId(), classId, sectionId, from, to)
                    : studentEnrollmentRepository.findRealizedByAcademicSessionAndClassOverlappingRange(
                            schoolId, session.getId(), classId, from, to);
        } catch (RuntimeException e) {
            log.error("Failed to resolve realized enrollment roster for class {} exam {}", className, exam.getId(), e);
            return;
        }
        for (StudentEnrollment row : rows) {
            roster.computeIfAbsent(row.getStudentId(), sid -> studentRepository.findByStudentIdAndSchoolId(sid, schoolId).orElse(null));
        }
        roster.values().removeIf(Objects::isNull);
    }

    private List<Student> resolveStudentsForSubject(ExamConfig exam, ExamSubjectEntry entry, Long sectionId) {
        String className = exam.getClassName();
        String subjectName = entry.getSubjectName();
        // Fetch students for the class — filtered by section if provided. Live ACTIVE roster is
        // the floor (preserves current-exam behavior); enrollment-authoritative augmentation
        // below adds back any student who was realized-enrolled in this class(+section) for the
        // subject's date but has since been promoted/transferred/withdrawn — so a historical
        // (not-yet-fully-marked) exam's roster stays complete for them too.
        List<Student> liveStudents = (sectionId != null)
                ? studentService.getActiveStudentsByClassAndSection(className, sectionId)
                : studentService.getActiveStudentsByClass(className);
        Map<String, Student> roster = new LinkedHashMap<>();
        liveStudents.forEach(s -> roster.put(s.getStudentId(), s));
        augmentRosterWithEnrollment(roster, securityUtil.getSchoolId(), exam, className, sectionId, List.of(entry));
        List<Student> all = new ArrayList<>(roster.values());

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
