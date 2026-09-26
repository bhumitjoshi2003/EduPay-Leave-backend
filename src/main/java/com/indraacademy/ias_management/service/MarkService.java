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
 * Results Phase 1: every percentage, grade, pass/fail and rank comes from {@link ResultCalculator}
 * over an {@link ExamSheet} — one exam's whole class with each student's applicable subjects — so
 * My Results, Class Results, the AI endpoints and report cards agree. Ranks are competition ranks by
 * percentage within the same exam, class and section; incomplete results are not ranked.
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
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;
    @Autowired private TeacherClassScopeService teacherClassScopeService;

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

        List<ExamSubjectEntry> entries = applicableEntriesForStudent(studentId, exam,
                examSubjectEntryRepository.findByExamConfigIdAndSchoolId(examConfigId, schoolId));

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

    // ─── Bulk mark save (atomic upsert) ───────────────────────────────────────

    /**
     * Saves a batch of marks all-or-nothing. Every entry is validated first — school, exam status
     * (published results are locked), academic session, teacher class/section scope, the student's
     * enrollment in the exam's class, that the student takes the subject, 0 ≤ marks ≤ max and no
     * duplicates — and if any entry fails, nothing is saved and every reason is reported
     * ({@link MarkValidationException}). A concurrent change to the same mark fails the whole
     * request (optimistic locking / the unique index) and rolls back.
     */
    @Transactional
    public MarkBulkResultDTO bulkSaveMarks(List<MarkEntryRequest> requests, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        String callerUserId = securityUtil.getUsername();
        String callerRole = securityUtil.getRole();
        String ip = httpRequest != null ? httpRequest.getRemoteAddr() : null;
        if (schoolId == null) throw new IllegalArgumentException("No school context for the current session.");
        boolean teacher = com.indraacademy.ias_management.config.Role.TEACHER.equals(callerRole);
        if (!teacher && !com.indraacademy.ias_management.config.Role.ADMIN.equals(callerRole)) {
            throw new org.springframework.security.access.AccessDeniedException("Only teachers and admins can enter marks.");
        }
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("Request body must be a non-empty list.");

        TeacherClassScopeService.TeacherScope teacherScope = null;
        if (teacher) {
            teacherScope = teacherClassScopeService.resolveOwnScope(callerUserId, schoolId);
            if (!teacherScope.hasClassResponsibility()) {
                throw new org.springframework.security.access.AccessDeniedException("You are not assigned as a class teacher.");
            }
            if (teacherScope.sectionRequiredButMissing()) {
                throw new org.springframework.security.access.AccessDeniedException(TeacherClassScopeService.SECTION_REQUIRED_MESSAGE);
            }
        }

        // Bulk-load every referenced subject entry and exam once.
        Set<Long> entryIds = requests.stream().filter(Objects::nonNull).map(MarkEntryRequest::getExamSubjectEntryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ExamSubjectEntry> entryById = new HashMap<>();
        for (ExamSubjectEntry e : examSubjectEntryRepository.findAllById(entryIds)) {
            if (schoolId.equals(e.getSchoolId())) entryById.put(e.getId(), e);
        }
        Map<Long, ExamConfig> examById = new HashMap<>();
        for (ExamConfig e : examConfigRepository.findAllById(entryById.values().stream().map(ExamSubjectEntry::getExamConfigId).collect(Collectors.toSet()))) {
            if (schoolId.equals(e.getSchoolId())) examById.put(e.getId(), e);
        }
        AcademicSession currentSession = sessionAccess.currentSessionOrNull(schoolId);

        List<MarkBulkResultDTO.MarkError> errors = new ArrayList<>();
        Map<Long, ExamSheet> sheetByExam = new HashMap<>();
        Map<Long, String> examProblem = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            MarkEntryRequest req = requests.get(i);
            String sid = req != null ? req.getStudentId() : null;
            Long entryId = req != null ? req.getExamSubjectEntryId() : null;
            String problem = null;
            if (req == null || sid == null || sid.isBlank()) problem = "studentId is required.";
            else if (entryId == null) problem = "examSubjectEntryId is required.";
            else if (req.getMarksObtained() == null) problem = "Marks are required.";
            ExamSubjectEntry entry = entryId != null ? entryById.get(entryId) : null;
            ExamConfig exam = entry != null ? examById.get(entry.getExamConfigId()) : null;
            if (problem == null && (entry == null || exam == null)) problem = "This exam subject was not found in your school.";
            if (problem == null) {
                final TeacherClassScopeService.TeacherScope scope = teacherScope;
                problem = examProblem.computeIfAbsent(exam.getId(), id -> examLevelProblem(exam, scope, currentSession, schoolId));
                if (problem != null && problem.isEmpty()) problem = null;
            }
            if (problem == null && !seen.add(sid + "|" + entryId)) problem = "This mark appears more than once in the request.";
            if (problem == null) {
                ExamSheet sheet = sheetByExam.computeIfAbsent(exam.getId(), id -> buildExamSheet(exam,
                        examSubjectEntryRepository.findByExamConfigIdAndSchoolId(exam.getId(), schoolId)));
                SheetRow row = sheet.row(sid);
                if (row == null) {
                    problem = "Student " + sid + " is not enrolled in class " + exam.getClassName() + " for this exam.";
                } else if (teacher && teacherScope.sectionId() != null && !teacherScope.sectionId().equals(row.sectionId())) {
                    problem = "Student " + sid + " is not in your section.";
                } else if (row.entries().stream().noneMatch(e -> e.getId().equals(entryId))) {
                    problem = "Student " + sid + " does not take " + entry.getSubjectName() + ".";
                } else if (req.getMarksObtained() < 0 || req.getMarksObtained() > entry.getMaxMarks()) {
                    problem = "Marks for " + entry.getSubjectName() + " must be between 0 and " + entry.getMaxMarks() + ".";
                }
            }
            if (problem != null) errors.add(new MarkBulkResultDTO.MarkError(i, sid, entryId, problem));
        }
        if (!errors.isEmpty()) throw new MarkValidationException(errors);

        // Save everything in this one transaction.
        Map<String, StudentMark> existing = new HashMap<>();
        for (StudentMark m : studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(new ArrayList<>(entryIds), schoolId)) {
            existing.put(m.getStudentId() + "|" + m.getExamSubjectEntryId(), m);
        }
        int saved = 0, updated = 0;
        for (MarkEntryRequest req : requests) {
            StudentMark mark = existing.get(req.getStudentId() + "|" + req.getExamSubjectEntryId());
            if (mark != null) {
                if (Objects.equals(mark.getMarksObtained(), req.getMarksObtained())) continue;
                String oldJson = toJson(mark);
                mark.setMarksObtained(req.getMarksObtained());
                mark.setUpdatedBy(callerUserId);
                studentMarkRepository.save(mark);
                auditService.logUpdate(callerUserId, callerRole, "UPDATE_STUDENT_MARK",
                        "STUDENT_MARK", String.valueOf(mark.getId()), oldJson, toJson(mark), ip);
                updated++;
            } else {
                mark = new StudentMark();
                mark.setSchoolId(schoolId);
                mark.setStudentId(req.getStudentId());
                mark.setExamSubjectEntryId(req.getExamSubjectEntryId());
                mark.setMarksObtained(req.getMarksObtained());
                mark.setCreatedBy(callerUserId);
                mark.setUpdatedBy(callerUserId);
                mark = studentMarkRepository.save(mark);
                auditService.log(callerUserId, callerRole, "CREATE_STUDENT_MARK",
                        "STUDENT_MARK", String.valueOf(mark.getId()), null, toJson(mark), ip);
                saved++;
            }
        }
        return new MarkBulkResultDTO(saved, updated, List.of());
    }

    /** Why no mark of this exam can be saved by the caller, or "" when the exam is writable. */
    private String examLevelProblem(ExamConfig exam, TeacherClassScopeService.TeacherScope teacherScope,
                                    AcademicSession currentSession, Long schoolId) {
        if (exam.isPublished()) {
            return "Results for " + exam.getExamName() + " are published and locked. Ask an admin to unpublish before changing marks.";
        }
        Optional<AcademicSession> examSession = academicSessionRepository.findBySchoolIdAndLabel(schoolId, exam.getSession());
        if (examSession.isEmpty()) {
            return "The exam's academic session " + exam.getSession() + " does not exist in your school.";
        }
        if (teacherScope != null) {
            if (currentSession == null || !currentSession.getId().equals(examSession.get().getId())) {
                return "Teachers can only enter marks for the current academic session.";
            }
            if (!exam.getClassName().equals(teacherScope.className())) {
                return "You can only enter marks for your own class.";
            }
        }
        return "";
    }

    // ─── Student results view ─────────────────────────────────────────────────

    /**
     * Full exam results for a student across all exams in a session (or all sessions if omitted).
     */
    @Transactional(readOnly = true)
    public List<ExamResultDTO> getStudentResults(String studentId, String session) {
        return getStudentResults(studentId, session, true);
    }

    /**
     * @param includeDrafts true for staff (admin/teacher); false for students and parents, who
     *                      only ever receive PUBLISHED exams — drafts never leave the server.
     */
    @Transactional(readOnly = true)
    public List<ExamResultDTO> getStudentResults(String studentId, String session, boolean includeDrafts) {
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

        if (!includeDrafts) exams.removeIf(e -> !e.isPublished());

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
            List<ExamSubjectEntry> allEntries = entries;

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

            // Canonical figures: the exam's whole-class sheet decides the student's applicable
            // subjects, score and rank exactly as Class Results does. A student outside today's
            // class roster (legacy history) still gets their own score, just without a rank.
            ExamSheet sheet = buildExamSheet(exam, allEntries);
            SheetRow row = sheet.row(studentId);
            List<ExamSubjectEntry> ownEntries = row != null ? row.entries() : entries;
            Map<Long, Double> ownMarks = row != null ? row.marks() : studentMarkRepository
                    .findByStudentIdAndExamSubjectEntryIdInAndSchoolId(studentId,
                            entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList()), schoolId)
                    .stream().collect(Collectors.toMap(StudentMark::getExamSubjectEntryId, StudentMark::getMarksObtained, (a, b) -> a));
            ResultCalculator.Score score = row != null ? row.score() : sheet.scoreOf(ownEntries, ownMarks);
            Long rankSection = row != null ? row.sectionId() : student.getSectionId();
            List<SheetRow> peers = sheet.rows().stream().filter(r -> Objects.equals(r.sectionId(), rankSection)).toList();

            List<SubjectResultDTO> subjectResults = new ArrayList<>();
            for (ExamSubjectEntry entry : ownEntries) {
                Double marksObtained = ownMarks.get(entry.getId());
                Map<String, Double> peerMarks = new HashMap<>();
                for (SheetRow peer : peers) {
                    Double m = peer.marks().get(entry.getId());
                    if (m != null) peerMarks.put(peer.student().getStudentId(), m);
                }
                Double avg = peerMarks.isEmpty() ? null
                        : ResultCalculator.round2(peerMarks.values().stream().mapToDouble(Double::doubleValue).average().orElse(0));
                Integer subjectRank = marksObtained == null || row == null ? null
                        : ResultCalculator.competitionRanks(peerMarks).get(studentId);
                SubjectResultDTO subjectDto = new SubjectResultDTO(entry.getSubjectName(), entry.getMaxMarks(), entry.getExamDate(),
                        marksObtained, avg, subjectRank);
                if (marksObtained != null && entry.getMaxMarks() != null && entry.getMaxMarks() > 0) {
                    double subjectPct = marksObtained / entry.getMaxMarks() * 100.0;
                    subjectDto.setGrade(GradingPolicy.grade(subjectPct, sheet.gradingSystem()));
                    subjectDto.setPassed(GradingPolicy.passed(subjectPct));
                }
                subjectResults.add(subjectDto);
            }

            ExamResultDTO dto = new ExamResultDTO(
                    exam.getId(), exam.getExamName(), exam.getClassName(), exam.getSession(),
                    student.getName(), subjectResults, score.obtained(), score.max(), score.percentage(),
                    row != null ? sheet.ranks().get(studentId) : null);
            applyScore(dto, exam, score);
            results.add(dto);
        }

        return results;
    }

    // ─── Class-wide results (teacher/admin) ───────────────────────────────────

    @Transactional(readOnly = true)
    public List<ClassStudentResultDTO> getClassResults(String className, Long examConfigId, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = examConfigRepository.findById(examConfigId)
                .filter(e -> schoolId.equals(e.getSchoolId()) && e.getClassName().equals(className))
                .orElseThrow(() -> new NoSuchElementException("Exam " + examConfigId + " not found for class " + className + "."));
        List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigIdAndSchoolId(examConfigId, schoolId);
        if (entries.isEmpty()) return Collections.emptyList();

        ExamSheet sheet = buildExamSheet(exam, entries);
        Map<Long, String> sectionNames = sectionNames(schoolId, sheet);
        List<ClassStudentResultDTO> results = new ArrayList<>();
        for (SheetRow row : sheet.rows()) {
            if (sectionId != null && !row.inSection(sectionId)) continue;
            List<ClassStudentResultDTO.SubjectMarkDTO> subjectMarks = row.entries().stream()
                    .map(e -> new ClassStudentResultDTO.SubjectMarkDTO(e.getSubjectName(), e.getMaxMarks(), e.getExamDate(),
                            row.marks().get(e.getId())))
                    .collect(Collectors.toList());
            ResultCalculator.Score score = row.score();
            ClassStudentResultDTO dto = new ClassStudentResultDTO(row.student().getStudentId(), row.student().getName(),
                    subjectMarks, score.obtained(), score.max(), score.percentage(), sheet.ranks().get(row.student().getStudentId()));
            dto.setResultStatus(exam.getResultStatus().name());
            dto.setComplete(score.complete());
            dto.setMarksMissing(score.marksMissing());
            dto.setGrade(score.grade());
            dto.setPassed(score.passed());
            dto.setSectionName(row.sectionId() != null ? sectionNames.get(row.sectionId()) : null);
            results.add(dto);
        }
        results.sort(Comparator.comparing((ClassStudentResultDTO r) -> r.getRank() == null)
                .thenComparing(r -> r.getRank() == null ? Integer.MAX_VALUE : r.getRank())
                .thenComparing(r -> r.getStudentName() == null ? "" : r.getStudentName(), String.CASE_INSENSITIVE_ORDER));
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
     * subject averages. Incomplete results (a mark not entered) are excluded from the
     * ranking and averages and listed in studentsWithNoMarksEntered instead, so they
     * never drag the class average down as if they'd scored zero.
     */
    @Transactional(readOnly = true)
    public ClassExamPerformanceDTO computeClassExamPerformance(String className, ExamConfig exam) {
        return computeClassExamPerformance(className, exam, null);
    }

    public ClassExamPerformanceDTO computeClassExamPerformance(String className, ExamConfig exam, Long sectionId) {
        List<ClassStudentResultDTO> results = getClassResults(className, exam.getId(), sectionId);

        List<ClassStudentResultDTO> scored = results.stream()
                .filter(ClassStudentResultDTO::isComplete)
                .collect(Collectors.toList());
        List<String> noMarksEntered = results.stream()
                .filter(r -> !r.isComplete())
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

        ClassExamPerformanceDTO dto = new ClassExamPerformanceDTO(className, exam.getExamName(), classAvg, ranked, noMarksEntered, subjectAverages);
        dto.setResultStatus(exam.getResultStatus().name());
        return dto;
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

    // ─── Canonical exam sheet (Results Phase 1) ────────────────────────────────

    /** One student's applicable subjects, marks and canonical score within an exam. */
    public record SheetRow(Student student, Long sectionId, Set<Long> sections, List<ExamSubjectEntry> entries,
                           Map<Long, Double> marks, ResultCalculator.Score score) {
        /** Whether the student belonged to this section for the exam (any realized segment). */
        public boolean inSection(Long sectionId) {
            return sections.contains(sectionId);
        }
    }

    /**
     * One exam's whole class: every student who should sit it, the subjects each takes, their
     * marks, canonical scores and ranks (competition rank by percentage within the same section).
     */
    public record ExamSheet(ExamConfig exam, List<ExamSubjectEntry> entries, List<SheetRow> rows,
                            Map<String, Integer> ranks, String gradingSystem) {
        public SheetRow row(String studentId) {
            for (SheetRow r : rows) if (r.student().getStudentId().equals(studentId)) return r;
            return null;
        }

        public ResultCalculator.Score scoreOf(List<ExamSubjectEntry> applicable, Map<Long, Double> marks) {
            return ResultCalculator.score(applicable.stream()
                    .map(e -> new ResultCalculator.SubjectMark(e.getMaxMarks(), marks.get(e.getId()))).toList(), gradingSystem);
        }
    }

    /**
     * Builds the canonical sheet in a few bulk queries (roster, stream/elective subject sets, all
     * marks of the exam). The roster is the class's current ACTIVE students for a current-session
     * exam, plus anyone realized-enrolled in the class for the exam's dates; a past-session exam
     * uses enrollment history only (falling back to today's class only for legacy data with no
     * enrollment records), so a class's later students never appear in an old exam.
     */
    public ExamSheet buildExamSheet(ExamConfig exam, List<ExamSubjectEntry> entries) {
        Long schoolId = securityUtil.getSchoolId();
        String gradingSystem = gradingSystem(schoolId);
        String className = exam.getClassName();

        Map<String, Student> roster = new LinkedHashMap<>();
        AcademicSession current = sessionAccess != null ? sessionAccess.currentSessionOrNull(schoolId) : null;
        boolean pastSessionExam = current != null && !current.getLabel().equals(exam.getSession())
                && academicSessionRepository.findBySchoolIdAndLabel(schoolId, exam.getSession()).isPresent();
        if (!pastSessionExam) {
            studentService.getActiveStudentsByClass(className).forEach(st -> roster.put(st.getStudentId(), st));
        }
        Set<String> live = new HashSet<>(roster.keySet());
        List<StudentEnrollment> enrollmentRows = augmentRosterWithEnrollment(roster, schoolId, exam, className, null, entries);
        if (pastSessionExam && roster.isEmpty()) {
            studentService.getActiveStudentsByClass(className).forEach(st -> { roster.put(st.getStudentId(), st); live.add(st.getStudentId()); });
        }
        // Section(s) for the exam: every realized enrollment segment in this class overlapping the
        // exam; the rank section is the latest segment's (the live section for legacy students).
        Map<String, List<StudentEnrollment>> segmentsByStudent = enrollmentRows.stream()
                .collect(Collectors.groupingBy(StudentEnrollment::getStudentId));
        List<Student> students = new ArrayList<>(roster.values());
        if (students.isEmpty() || entries.isEmpty()) return new ExamSheet(exam, entries, List.of(), Map.of(), gradingSystem);

        Map<String, Set<String>> streamSubjectsByStudent = batchLoadStudentSubjectSets(students);
        boolean hasStreamStudents = streamSubjectsByStudent.values().stream().anyMatch(v -> !v.isEmpty());
        Set<String> electiveNames = hasStreamStudents ? Set.of()
                : classSubjectRepository.findByClassNameAndOptionalTrueAndSchoolId(className, schoolId).stream()
                        .map(ClassSubject::getSubjectName).collect(Collectors.toSet());
        Map<String, Set<String>> enrollmentsByStudent = new HashMap<>();
        if (!electiveNames.isEmpty()) {
            studentElectiveEnrollmentRepository.findByClassNameAndSchoolId(className, schoolId)
                    .forEach(e -> enrollmentsByStudent.computeIfAbsent(e.getStudentId(), k -> new HashSet<>()).add(e.getSubjectName()));
        }

        Map<String, Map<Long, Double>> markMap = new HashMap<>();
        for (StudentMark m : studentMarkRepository.findByExamSubjectEntryIdInAndSchoolId(
                entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList()), schoolId)) {
            if (m.getMarksObtained() != null) {
                markMap.computeIfAbsent(m.getStudentId(), k -> new HashMap<>()).put(m.getExamSubjectEntryId(), m.getMarksObtained());
            }
        }

        List<SheetRow> rows = new ArrayList<>();
        Map<String, Double> percentageByStudent = new HashMap<>();
        Map<String, Long> sectionByStudent = new HashMap<>();
        for (Student student : students) {
            List<ExamSubjectEntry> applicable = filterEntriesForStudent(entries, student, hasStreamStudents,
                    streamSubjectsByStudent, electiveNames, enrollmentsByStudent);
            Map<Long, Double> own = new HashMap<>();
            Map<Long, Double> all = markMap.getOrDefault(student.getStudentId(), Map.of());
            applicable.forEach(e -> { if (all.containsKey(e.getId())) own.put(e.getId(), all.get(e.getId())); });
            ResultCalculator.Score score = ResultCalculator.score(applicable.stream()
                    .map(e -> new ResultCalculator.SubjectMark(e.getMaxMarks(), own.get(e.getId()))).toList(), gradingSystem);
            List<StudentEnrollment> segments = segmentsByStudent.getOrDefault(student.getStudentId(), List.of());
            Set<Long> sections = new HashSet<>();
            segments.forEach(seg -> sections.add(seg.getSectionId()));
            Long rankSection = segments.stream()
                    .max(Comparator.comparing(StudentEnrollment::getEffectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(StudentEnrollment::getSectionId)
                    .orElse(student.getSectionId());
            if (live.contains(student.getStudentId())) {
                sections.add(student.getSectionId());
                if (segments.isEmpty()) rankSection = student.getSectionId();
            }
            if (sections.isEmpty()) sections.add(rankSection);
            rows.add(new SheetRow(student, rankSection, sections, applicable, own, score));
            percentageByStudent.put(student.getStudentId(), score.percentage());
            sectionByStudent.put(student.getStudentId(), rankSection);
        }
        Map<String, Integer> ranks = ResultCalculator.competitionRanksWithin(percentageByStudent, sectionByStudent::get);
        return new ExamSheet(exam, entries, rows, ranks, gradingSystem);
    }

    /**
     * The subjects of an exam one student actually takes: their stream's subjects (classes 11–12)
     * or every non-elective subject plus the electives they enrolled in for the exam's own class.
     * Shared with the report-card engine so both use the same applicability rule.
     */
    @Transactional(readOnly = true)
    public List<ExamSubjectEntry> applicableEntriesForStudent(String studentId, ExamConfig exam, List<ExamSubjectEntry> entries) {
        Long schoolId = securityUtil.getSchoolId();
        Set<String> streamSubjects = loadStudentSubjectSet(studentId);
        if (!streamSubjects.isEmpty()) {
            return entries.stream().filter(e -> streamSubjects.contains(e.getSubjectName().toLowerCase())).collect(Collectors.toList());
        }
        Set<String> electiveNames = classSubjectRepository.findByClassNameAndOptionalTrueAndSchoolId(exam.getClassName(), schoolId)
                .stream().map(ClassSubject::getSubjectName).collect(Collectors.toSet());
        if (electiveNames.isEmpty()) return entries;
        Set<String> enrolled = studentElectiveEnrollmentRepository.findByStudentIdAndSchoolId(studentId, schoolId).stream()
                .filter(en -> exam.getClassName().equals(en.getClassName()))
                .map(StudentElectiveEnrollment::getSubjectName).collect(Collectors.toSet());
        return entries.stream().filter(e -> !electiveNames.contains(e.getSubjectName()) || enrolled.contains(e.getSubjectName()))
                .collect(Collectors.toList());
    }

    /** The school's grading system (CBSE / LETTER / PERCENTAGE), as used on report cards. */
    public String gradingSystem(Long schoolId) {
        if (schoolId == null || schoolRepository == null) return null;
        return schoolRepository.findById(schoolId).map(School::getGradingSystem).orElse(null);
    }

    private Map<Long, String> sectionNames(Long schoolId, ExamSheet sheet) {
        Set<Long> ids = sheet.rows().stream().map(SheetRow::sectionId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty() || sectionRepository == null) return names;
        for (Section section : sectionRepository.findAllById(ids)) {
            if (schoolId.equals(section.getSchoolId())) names.put(section.getId(), section.getName());
        }
        return names;
    }

    private static void applyScore(ExamResultDTO dto, ExamConfig exam, ResultCalculator.Score score) {
        dto.setResultStatus(exam.getResultStatus() != null ? exam.getResultStatus().name() : ExamResultStatus.DRAFT.name());
        dto.setComplete(score.complete());
        dto.setMarksMissing(score.marksMissing());
        dto.setGrade(score.grade());
        dto.setPassed(score.passed());
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
    private List<StudentEnrollment> augmentRosterWithEnrollment(Map<String, Student> roster, Long schoolId, ExamConfig exam,
                                             String className, Long sectionId, List<ExamSubjectEntry> entries) {
        Optional<AcademicSession> sessionOpt = academicSessionRepository.findBySchoolIdAndLabel(schoolId, exam.getSession());
        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className).map(SchoolClass::getId).orElse(null);
        if (sessionOpt.isEmpty() || classId == null) return List.of();
        AcademicSession session = sessionOpt.get();
        LocalDate from = entries.stream().map(ExamSubjectEntry::getExamDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(session.getStartDate());
        LocalDate to = entries.stream().map(ExamSubjectEntry::getExamDate).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(session.getEndDate());
        if (from == null || to == null || to.isBefore(from)) return List.of();

        List<StudentEnrollment> rows;
        try {
            rows = (sectionId != null)
                    ? studentEnrollmentRepository.findRealizedByAcademicSessionAndClassAndSectionOverlappingRange(
                            schoolId, session.getId(), classId, sectionId, from, to)
                    : studentEnrollmentRepository.findRealizedByAcademicSessionAndClassOverlappingRange(
                            schoolId, session.getId(), classId, from, to);
        } catch (RuntimeException e) {
            log.error("Failed to resolve realized enrollment roster for class {} exam {}", className, exam.getId(), e);
            return List.of();
        }
        for (StudentEnrollment row : rows) {
            roster.computeIfAbsent(row.getStudentId(), sid -> studentRepository.findByStudentIdAndSchoolId(sid, schoolId).orElse(null));
        }
        roster.values().removeIf(Objects::isNull);
        return rows;
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

    private double round2(double value) {
        return ResultCalculator.round2(value);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
