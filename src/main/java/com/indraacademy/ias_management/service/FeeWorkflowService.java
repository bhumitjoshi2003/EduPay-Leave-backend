package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.FeeWorkflowDtos.*;
import com.indraacademy.ias_management.dto.RecalculationEntryDto;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FeeWorkflowService {
    private final SchoolFeeSettingsRepository settingsRepository;
    private final StudentFeeAssignmentRepository assignmentRepository;
    private final StudentTransportFeeAssignmentRepository transportRepository;
    private final StudentRepository studentRepository;
    private final StudentFeesRepository studentFeesRepository;
    private final StudentFeesLineItemRepository lineItemRepository;
    private final StudentOneTimeFeeChargedRepository oneTimeRepository;
    private final SchoolRepository schoolRepository;
    private final AcademicSessionRepository academicSessionRepository;
    private final FeeHeadRepository feeHeadRepository;
    private final StudentFeeConfigRepository feeConfigRepository;
    private final FeeGenerationBatchRepository generationBatchRepository;
    private final FeeCalculationService calculationService;
    private final StudentFeesRecalculationService recalculationService;
    private final AuditService auditService;
    private final SecurityUtil securityUtil;
    private final TransactionTemplate transactionTemplate;

    public FeeWorkflowService(SchoolFeeSettingsRepository settingsRepository,
                              StudentFeeAssignmentRepository assignmentRepository,
                              StudentTransportFeeAssignmentRepository transportRepository,
                              StudentRepository studentRepository,
                              StudentFeesRepository studentFeesRepository,
                              StudentFeesLineItemRepository lineItemRepository,
                              StudentOneTimeFeeChargedRepository oneTimeRepository,
                              SchoolRepository schoolRepository,
                              AcademicSessionRepository academicSessionRepository,
                              FeeHeadRepository feeHeadRepository,
                              StudentFeeConfigRepository feeConfigRepository,
                              FeeGenerationBatchRepository generationBatchRepository,
                              FeeCalculationService calculationService,
                              StudentFeesRecalculationService recalculationService,
                              AuditService auditService,
                              SecurityUtil securityUtil,
                              PlatformTransactionManager transactionManager) {
        this.settingsRepository = settingsRepository;
        this.assignmentRepository = assignmentRepository;
        this.transportRepository = transportRepository;
        this.studentRepository = studentRepository;
        this.studentFeesRepository = studentFeesRepository;
        this.lineItemRepository = lineItemRepository;
        this.oneTimeRepository = oneTimeRepository;
        this.schoolRepository = schoolRepository;
        this.academicSessionRepository = academicSessionRepository;
        this.feeHeadRepository = feeHeadRepository;
        this.feeConfigRepository = feeConfigRepository;
        this.generationBatchRepository = generationBatchRepository;
        this.calculationService = calculationService;
        this.recalculationService = recalculationService;
        this.auditService = auditService;
        this.securityUtil = securityUtil;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public SchoolFeeSettings getSettings() {
        Long schoolId = securityUtil.getSchoolId();
        return settingsRepository.findBySchoolId(schoolId).orElseGet(() -> {
            SchoolFeeSettings settings = new SchoolFeeSettings();
            settings.setSchoolId(schoolId);
            settings.setOperationalStatus(FeeOperationalStatus.ACTIVE);
            return settingsRepository.save(settings);
        });
    }

    @Transactional
    public SchoolFeeSettings updateSettings(SettingsUpdate request, String ip) {
        if (request == null) throw new IllegalArgumentException("Fee policy is required.");
        SchoolFeeSettings settings = getSettings();
        FeeOperationalStatus old = settings.getOperationalStatus();
        // The simplified workflow is always admin-triggered and active. School admins
        // only choose how a mid-month effective date should be billed.
        settings.setOperationalStatus(FeeOperationalStatus.ACTIVE);
        if (request.activationDate() != null) settings.setActivationDate(request.activationDate());
        if (request.midSessionPolicy() != null) settings.setMidSessionPolicy(request.midSessionPolicy());
        if (request.allowRetroactiveGeneration() != null) settings.setAllowRetroactiveGeneration(request.allowRetroactiveGeneration());
        // Fee generation is deliberately admin-triggered. Retain the legacy column for compatibility,
        // but never allow settings updates to activate unattended annual generation.
        settings.setAutomaticAnnualGeneration(false);
        SchoolFeeSettings saved = settingsRepository.save(settings);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "UPDATE_FEE_OPERATION_SETTINGS",
                "SchoolFeeSettings", String.valueOf(saved.getSchoolId()), String.valueOf(old),
                saved.getOperationalStatus() + ", activationDate=" + saved.getActivationDate(), ip);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AssignmentRow> listAssignments(String session, String className, StudentFeeAssignmentStatus status) {
        validateSession(session);
        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = className == null || className.isBlank()
                ? studentRepository.findBySchoolId(schoolId)
                : studentRepository.findByClassNameAndSchoolId(className, schoolId);
        students = students.stream().filter(student -> student.getStatus() == null
                || !student.getStatus().isExitStatus()).toList();
        Map<String, StudentFeeAssignment> assignments = assignmentRepository.findBySchoolIdAndAcademicSession(schoolId, session)
                .stream().collect(Collectors.toMap(StudentFeeAssignment::getStudentId, Function.identity()));
        Map<String, List<Integer>> generatedByStudent = studentFeesRepository.findBySchoolIdAndYear(schoolId, session).stream()
                .collect(Collectors.groupingBy(StudentFees::getStudentId,
                        Collectors.mapping(StudentFees::getMonth, Collectors.collectingAndThen(Collectors.toSet(),
                                values -> values.stream().sorted().toList()))));
        List<AssignmentRow> rows = new ArrayList<>();
        for (Student student : students) {
            StudentFeeAssignment assignment = assignments.get(student.getStudentId());
            int generated = generatedByStudent.getOrDefault(student.getStudentId(), List.of()).size();
            List<Integer> generatedMonths = generatedByStudent.getOrDefault(student.getStudentId(), List.of());
            StudentFeeAssignmentStatus resolved = resolveAssignmentStatus(assignment, generatedMonths);
            if (status != null && resolved != status) continue;
            rows.add(new AssignmentRow(student.getStudentId(), student.getName(), student.getClassName(),
                    student.getSectionName(), student.getJoiningDate(), resolved,
                    assignment != null ? assignment.getEffectiveDate() : null,
                    assignment != null ? parseMonths(assignment.getSelectedMonths()) : List.of(), generated,
                    assignment != null ? firstNonBlank(assignment.getFailureReason(), assignment.getExclusionReason()) : null));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public AssignmentSummary summary(String session) {
        List<AssignmentRow> rows = listAssignments(session, null, null);
        return new AssignmentSummary(rows.size(), count(rows, StudentFeeAssignmentStatus.NOT_ASSIGNED),
                count(rows, StudentFeeAssignmentStatus.READY), count(rows, StudentFeeAssignmentStatus.GENERATED),
                count(rows, StudentFeeAssignmentStatus.PARTIALLY_GENERATED), count(rows, StudentFeeAssignmentStatus.EXCLUDED),
                count(rows, StudentFeeAssignmentStatus.GENERATION_FAILED));
    }

    @Transactional
    public List<StudentFeeAssignment> assign(AssignmentRequest request, boolean excluded, String ip) {
        validateAssignmentRequest(request);
        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = requireStudents(schoolId, request.studentIds());
        List<Integer> months = normalizeMonths(request.months());
        SchoolFeeSettings settings = getSettings();
        List<StudentFeeAssignment> saved = new ArrayList<>();
        for (Student student : students) {
            PolicyContext policy = resolvePolicyContext(student, request.academicSession(), request.effectiveDate(),
                    request.midSessionPolicy(), settings);
            StudentFeeAssignment assignment = assignmentRepository
                    .findBySchoolIdAndStudentIdAndAcademicSession(schoolId, student.getStudentId(), request.academicSession())
                    .orElseGet(StudentFeeAssignment::new);
            assignment.setSchoolId(schoolId);
            assignment.setStudentId(student.getStudentId());
            assignment.setAcademicSession(request.academicSession());
            assignment.setEffectiveDate(policy.effectiveDate());
            assignment.setSelectedMonths(joinMonths(months));
            assignment.setExcluded(excluded);
            assignment.setExclusionReason(excluded ? requireReason(request.reason()) : null);
            assignment.setFailureReason(null);
            List<Integer> generatedMonths = studentFeesRepository
                    .findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(student.getStudentId(), schoolId,
                            request.academicSession())
                    .stream().map(StudentFees::getMonth).distinct().toList();
            assignment.setStatus(excluded ? StudentFeeAssignmentStatus.EXCLUDED
                    : statusForMonths(months, generatedMonths, true));
            assignment.setAssignedBy(securityUtil.getUsername());
            assignment.setAssignedAt(LocalDateTime.now());
            saved.add(assignmentRepository.save(assignment));
        }
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(),
                excluded ? "EXCLUDE_STUDENT_FROM_FEES" : "ASSIGN_STUDENT_FEES", "StudentFeeAssignment",
                request.academicSession(), null, request.studentIds() + ", months=" + months + ", effective=" + request.effectiveDate(), ip);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<StudentPreview> preview(AssignmentRequest request) {
        validateAssignmentRequest(request);
        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = requireStudents(schoolId, request.studentIds());
        List<Integer> months = normalizeMonths(request.months());
        SchoolFeeSettings settings = getSettings();
        return students.stream().map(s -> previewStudent(s, request.academicSession(), request.effectiveDate(),
                months, request.midSessionPolicy(), settings)).toList();
    }

    public List<GenerationResult> generate(AssignmentRequest request, String ip) {
        return generate(request, ip, null);
    }

    private List<GenerationResult> generate(AssignmentRequest request, String ip, Long retryOfBatchId) {
        validateAssignmentRequest(request);
        SchoolFeeSettings settings = getSettings();
        if (settings.getOperationalStatus() != FeeOperationalStatus.ACTIVE) {
            throw new IllegalStateException("Fees must be ACTIVE before charges can be generated.");
        }
        if (!settings.isAllowRetroactiveGeneration() && settings.getActivationDate() != null
                && request.effectiveDate().isBefore(settings.getActivationDate())) {
            throw new IllegalArgumentException("Retroactive generation is disabled. Effective date cannot precede the fee activation date.");
        }
        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = requireStudents(schoolId, request.studentIds());
        List<Integer> months = normalizeMonths(request.months());
        FeeGenerationBatch batch = new FeeGenerationBatch();
        batch.setSchoolId(schoolId); batch.setAcademicSession(request.academicSession());
        batch.setEffectiveDate(request.effectiveDate()); batch.setSelectedMonths(joinMonths(months));
        batch.setRequestedStudentIds(String.join(",", students.stream().map(Student::getStudentId).toList()));
        batch.setRequestedStudents(students.size()); batch.setStatus("RUNNING");
        batch.setInitiatedBy(securityUtil.getUsername()); batch.setRetryOfBatchId(retryOfBatchId);
        batch.setStartedAt(LocalDateTime.now());
        batch = generationBatchRepository.save(batch);
        List<GenerationResult> results = new ArrayList<>();
        for (Student student : students) {
            try {
                GenerationResult result = transactionTemplate.execute(status ->
                        generateForStudent(student, request.academicSession(), request.effectiveDate(), months,
                                request.midSessionPolicy(), settings));
                results.add(Objects.requireNonNull(result));
            } catch (RuntimeException ex) {
                transactionTemplate.executeWithoutResult(status ->
                        markGenerationFailed(schoolId, student.getStudentId(), request.academicSession(), ex));
                results.add(new GenerationResult(student.getStudentId(), 0, 0, false, safeMessage(ex)));
            }
        }
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "GENERATE_ASSIGNED_STUDENT_FEES",
                "StudentFees", request.academicSession(), null,
                request.studentIds() + ", months=" + months + ", batchId=" + batch.getId(), ip);
        List<String> failedIds = results.stream().filter(value -> !value.successful()).map(GenerationResult::studentId).toList();
        batch.setSuccessfulStudents(results.size() - failedIds.size()); batch.setFailedStudents(failedIds.size());
        batch.setGeneratedMonths(results.stream().mapToInt(GenerationResult::generated).sum());
        batch.setSkippedMonths(results.stream().mapToInt(GenerationResult::skipped).sum());
        batch.setFailedStudentIds(failedIds.isEmpty() ? null : String.join(",", failedIds));
        batch.setStatus(failedIds.isEmpty() ? "COMPLETED" : failedIds.size() == results.size() ? "FAILED" : "PARTIAL");
        batch.setCompletedAt(LocalDateTime.now()); generationBatchRepository.save(batch);
        return results;
    }

    @Transactional(readOnly = true)
    public List<GenerationBatchRow> generationBatches(String session) {
        validateSession(session);
        return generationBatchRepository.findTop25BySchoolIdAndAcademicSessionOrderByStartedAtDesc(
                securityUtil.getSchoolId(), session).stream().map(this::toGenerationBatchRow).toList();
    }

    public List<GenerationResult> retryGenerationBatch(Long batchId, String ip) {
        Long schoolId = securityUtil.getSchoolId();
        FeeGenerationBatch batch = generationBatchRepository.findByIdAndSchoolId(batchId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Generation batch not found."));
        List<String> failed = parseCsv(batch.getFailedStudentIds());
        if (failed.isEmpty()) throw new IllegalStateException("This batch has no failed students to retry.");
        AssignmentRequest request = new AssignmentRequest(failed, batch.getAcademicSession(),
                batch.getEffectiveDate(), parseMonths(batch.getSelectedMonths()), "Retry of batch " + batchId, null);
        return generate(request, ip, batchId);
    }

    @Transactional(readOnly = true)
    public ReconciliationSummary reconciliation(String session) {
        validateSession(session);
        Long schoolId = securityUtil.getSchoolId();
        Map<String, StudentFeeAssignment> assignments = assignmentRepository.findBySchoolIdAndAcademicSession(schoolId, session)
                .stream().collect(Collectors.toMap(StudentFeeAssignment::getStudentId, Function.identity()));
        Map<String, List<Integer>> generatedByStudent = studentFeesRepository.findBySchoolIdAndYear(schoolId, session).stream()
                .collect(Collectors.groupingBy(StudentFees::getStudentId,
                        Collectors.mapping(StudentFees::getMonth, Collectors.collectingAndThen(Collectors.toSet(),
                                values -> values.stream().sorted().toList()))));
        List<ReconciliationRow> rows = new ArrayList<>();
        int full = 0, partial = 0, unassigned = 0, failed = 0, missingTotal = 0;
        for (Student student : studentRepository.findBySchoolId(schoolId)) {
            StudentFeeAssignment assignment = assignments.get(student.getStudentId());
            List<Integer> generated = generatedByStudent.getOrDefault(student.getStudentId(), List.of());
            List<Integer> assigned = assignment == null ? List.of() : parseMonths(assignment.getSelectedMonths());
            List<Integer> missing = assigned.stream().filter(month -> !generated.contains(month)).toList();
            StudentFeeAssignmentStatus status = assignment == null ? deriveStatus(student.getStudentId(), schoolId, session) : assignment.getStatus();
            if (assignment == null || status == StudentFeeAssignmentStatus.NOT_ASSIGNED) unassigned++;
            else if (status == StudentFeeAssignmentStatus.GENERATION_FAILED) failed++;
            else if (!assigned.isEmpty() && missing.isEmpty()) full++;
            else partial++;
            missingTotal += missing.size();
            rows.add(new ReconciliationRow(student.getStudentId(), student.getName(), student.getClassName(), status,
                    assigned, generated, missing, assignment == null ? "Student has not been assigned for fees."
                    : firstNonBlank(assignment.getFailureReason(), assignment.getExclusionReason())));
        }
        return new ReconciliationSummary(rows.size(), full, partial, unassigned, failed, missingTotal, rows);
    }

    @Transactional(readOnly = true)
    public FeeReadinessReport readiness(String session) {
        validateSession(session);
        Long schoolId = securityUtil.getSchoolId();
        List<ReadinessIssue> issues = new ArrayList<>();
        SchoolFeeSettings settings = settingsRepository.findBySchoolId(schoolId).orElse(null);
        if (settings == null || settings.getOperationalStatus() != FeeOperationalStatus.ACTIVE) {
            issues.add(new ReadinessIssue("BLOCKER", "FEES_NOT_ACTIVE",
                    "Fee operations must be ACTIVE before charges can be generated.", null, null));
        }
        if (academicSessionRepository.findBySchoolIdAndLabel(schoolId, session).isEmpty()) {
            issues.add(new ReadinessIssue("BLOCKER", "SESSION_MISSING",
                    "The selected academic session is not configured for this school.", null, null));
        }

        List<AssignmentRow> rows = listAssignments(session, null, null);
        Map<String, Long> studentsByClass = rows.stream()
                .filter(row -> row.status() != StudentFeeAssignmentStatus.EXCLUDED)
                .collect(Collectors.groupingBy(AssignmentRow::className, TreeMap::new, Collectors.counting()));
        int configuredClasses = 0;
        int missingClasses = 0;
        for (Map.Entry<String, Long> entry : studentsByClass.entrySet()) {
            FeeCalculationService.FeeConfigurationStatus status = calculationService
                    .validateFeeConfiguration(schoolId, session, entry.getKey());
            if (status.valid()) configuredClasses++;
            else {
                missingClasses++;
                issues.add(new ReadinessIssue("BLOCKER", "FEE_STRUCTURE_MISSING",
                        "Configure at least one fee rule for this class and session.", entry.getKey(),
                        Math.toIntExact(entry.getValue())));
            }
        }
        int unassigned = Math.toIntExact(rows.stream().filter(row -> row.status() == StudentFeeAssignmentStatus.NOT_ASSIGNED).count());
        int failed = Math.toIntExact(rows.stream().filter(row -> row.status() == StudentFeeAssignmentStatus.GENERATION_FAILED).count());
        if (unassigned > 0) issues.add(new ReadinessIssue("WARNING", "STUDENTS_NOT_ASSIGNED",
                "Students remain unassigned. Assign or explicitly exclude them before rollout.", null, unassigned));
        if (failed > 0) issues.add(new ReadinessIssue("BLOCKER", "GENERATION_FAILURES",
                "Resolve or retry failed fee-generation students.", null, failed));
        int blockers = Math.toIntExact(issues.stream().filter(issue -> "BLOCKER".equals(issue.severity())).count());
        int warnings = issues.size() - blockers;
        return new FeeReadinessReport(session, blockers == 0, blockers, warnings, configuredClasses,
                missingClasses, unassigned, failed, issues);
    }

    public WorkflowChangeResult changeTransport(TransportChangeRequest request, String ip) {
        if (request == null || request.studentIds() == null || request.studentIds().isEmpty()
                || request.effectiveFrom() == null) throw new IllegalArgumentException("Students and effective date are required.");
        validateSession(request.academicSession());
        if (request.enabled() && (request.distance() == null || request.distance() <= 0)) {
            throw new IllegalArgumentException("A positive distance is required when bus service is enabled.");
        }
        String reason = requireReason(request.reason());
        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = requireStudents(schoolId, request.studentIds());
        List<StudentRecalculationResult> results = new ArrayList<>();
        for (Student student : students) {
            try {
                StudentRecalculationResult result = transactionTemplate.execute(status ->
                        applyTransportForStudent(student, request, reason, ip));
                results.add(Objects.requireNonNull(result));
            } catch (RuntimeException ex) {
                results.add(new StudentRecalculationResult(student.getStudentId(), false, List.of(), safeMessage(ex)));
            }
        }
        return summarizeChanges(students.size(), results);
    }

    @Transactional(readOnly = true)
    public List<StudentTransportFeeAssignment> transportHistory(String studentId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
        return transportRepository.findBySchoolIdAndStudentIdAndAcademicSessionOrderByEffectiveFromDesc(schoolId, studentId, session);
    }

    @Transactional(readOnly = true)
    public FeeLifecycleHistory lifecycleHistory(String studentId, String sessionLabel) {
        Long schoolId = securityUtil.getSchoolId();
        studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));
        AcademicSession session = academicSessionRepository.findBySchoolIdAndLabel(schoolId, sessionLabel)
                .orElseThrow(() -> new IllegalArgumentException("Academic session not found."));
        List<DiscountHistoryRow> discounts = feeConfigRepository
                .findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByValidFromDescIdDesc(schoolId, studentId, session.getId())
                .stream().map(this::toDiscountHistory).toList();
        List<TransportHistoryRow> transport = transportRepository
                .findBySchoolIdAndStudentIdAndAcademicSessionOrderByEffectiveFromDesc(schoolId, studentId, sessionLabel)
                .stream().map(this::toTransportHistory).toList();
        return new FeeLifecycleHistory(studentId, sessionLabel, discounts, transport);
    }

    @Transactional
    public DiscountHistoryRow updateFutureDiscount(Long configId, DiscountUpdateRequest request, String ip) {
        validateDiscountUpdate(request);
        Long schoolId = securityUtil.getSchoolId();
        StudentFeeConfig config = feeConfigRepository.findById(configId)
                .filter(value -> Objects.equals(value.getSchoolId(), schoolId))
                .orElseThrow(() -> new IllegalArgumentException("Discount configuration not found."));
        if (config.getRevokedAt() != null || config.getValidFrom() == null || !config.getValidFrom().isAfter(LocalDate.now())) {
            throw new IllegalStateException("Only a future discount can be edited. Expire an active discount instead.");
        }
        LocalDate from = monthStart(request.validFrom());
        LocalDate until = monthEnd(request.validUntil());
        if (!from.isAfter(LocalDate.now())) throw new IllegalArgumentException("The updated start month must be in the future.");
        if (until != null && until.isBefore(from)) throw new IllegalArgumentException("End month cannot precede start month.");
        if (feeConfigRepository.existsOverlappingExcluding(configId, schoolId, config.getStudentId(),
                config.getAcademicSession().getId(), config.getFeeHead().getId(), from, until)) {
            throw new IllegalArgumentException("An overlapping discount already exists.");
        }
        config.setConfigType(request.configType()); config.setValue(request.value());
        config.setValidFrom(from); config.setValidUntil(until); config.setReason(requireReason(request.reason()));
        feeConfigRepository.save(config);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "UPDATE_FUTURE_STUDENT_DISCOUNT",
                "StudentFeeConfig", String.valueOf(configId), null, "from=" + from + ", until=" + until, ip);
        return toDiscountHistory(config);
    }

    public WorkflowChangeResult endDiscount(Long configId, EndDiscountRequest request, String ip) {
        Long schoolId = securityUtil.getSchoolId();
        String suppliedReason = request == null || request.reason() == null ? "" : request.reason().trim();
        String auditReason = suppliedReason.isBlank() ? "Removed from fee assignment." : suppliedReason;
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            StudentFeeConfig config = feeConfigRepository.findById(configId)
                    .filter(value -> Objects.equals(value.getSchoolId(), schoolId))
                    .orElseThrow(() -> new IllegalArgumentException("Discount or waiver not found."));
            if (config.getRevokedAt() != null) {
                throw new IllegalStateException("This discount or waiver has already ended.");
            }
            config.setRevokedAt(LocalDateTime.now());
            config.setRevokedBy(securityUtil.getUsername());
            config.setRevokeReason(auditReason);
            feeConfigRepository.save(config);

            String session = config.getAcademicSession().getLabel();
            LocalDate adjustmentStart = config.getValidFrom() == null
                    ? sessionStart(session, schoolId)
                    : config.getValidFrom();
            List<Integer> affectedMonths = generatedMonthsInRange(config.getStudentId(), session,
                    adjustmentStart, config.getValidUntil());
            List<RecalculationEntryDto> recalculated = affectedMonths.stream()
                    .map(month -> recalculationService.recalculateOne(config.getStudentId(), session, month,
                            auditReason, ip))
                    .toList();
            auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "REMOVE_STUDENT_FEE_ADJUSTMENT",
                    "StudentFeeConfig", String.valueOf(configId), null, auditReason, ip);
            return summarizeChanges(1, List.of(new StudentRecalculationResult(config.getStudentId(), true,
                    recalculated, affectedMonths.isEmpty()
                    ? "Adjustment removed; no generated fee records required recalculation." : null)));
        }));
    }

    @Transactional
    public TransportHistoryRow correctFutureTransport(Long assignmentId, TransportCorrectionRequest request, String ip) {
        if (request == null) throw new IllegalArgumentException("Correction details are required.");
        if (request.enabled() && (request.distance() == null || request.distance() <= 0))
            throw new IllegalArgumentException("A positive distance is required when transport is enabled.");
        String reason = requireReason(request.reason());
        Long schoolId = securityUtil.getSchoolId();
        StudentTransportFeeAssignment value = transportRepository.findByIdAndSchoolId(assignmentId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Transport assignment not found."));
        if (!value.getEffectiveFrom().isAfter(LocalDate.now())) {
            throw new IllegalStateException("Only a future transport entry can be corrected. Add a new effective-dated change instead.");
        }
        value.setEnabled(request.enabled()); value.setDistance(request.enabled() ? request.distance() : null);
        value.setReason(reason); value.setChangedBy(securityUtil.getUsername()); transportRepository.save(value);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "CORRECT_FUTURE_TRANSPORT_ASSIGNMENT",
                "StudentTransportFeeAssignment", String.valueOf(assignmentId), null,
                "enabled=" + request.enabled() + ", distance=" + request.distance(), ip);
        return toTransportHistory(value);
    }

    public WorkflowChangeResult applyBulkDiscount(BulkDiscountRequest request, String ip) {
        validateBulkDiscount(request);
        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = requireStudents(schoolId, request.studentIds());
        AcademicSession session = academicSessionRepository.findById(request.academicSessionId())
                .filter(value -> Objects.equals(value.getSchoolId(), schoolId))
                .orElseThrow(() -> new IllegalArgumentException("Academic session not found."));
        FeeHead feeHead = feeHeadRepository.findByIdAndSchoolId(request.feeHeadId(), schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Fee head not found."));
        List<StudentRecalculationResult> results = new ArrayList<>();
        for (Student student : students) {
            try {
                StudentRecalculationResult result = transactionTemplate.execute(status ->
                        applyDiscountForStudent(student, session, feeHead, request, ip));
                results.add(Objects.requireNonNull(result));
            } catch (RuntimeException ex) {
                results.add(new StudentRecalculationResult(student.getStudentId(), false, List.of(), safeMessage(ex)));
            }
        }
        return summarizeChanges(students.size(), results);
    }

    private StudentRecalculationResult applyTransportForStudent(Student student, TransportChangeRequest request,
                                                                 String reason, String ip) {
        Long schoolId = securityUtil.getSchoolId();
        List<StudentTransportFeeAssignment> history = transportRepository
                .findBySchoolIdAndStudentIdAndAcademicSessionOrderByEffectiveFromDesc(
                        schoolId, student.getStudentId(), request.academicSession());
        if (history.stream().anyMatch(value -> value.getEffectiveFrom().equals(request.effectiveFrom()))) {
            throw new IllegalArgumentException("A transport change already exists on this effective date.");
        }
        LocalDate effectiveUntil = history.stream()
                .map(StudentTransportFeeAssignment::getEffectiveFrom)
                .filter(value -> value.isAfter(request.effectiveFrom()))
                .min(LocalDate::compareTo)
                .map(value -> value.minusDays(1))
                .orElse(null);
        history.stream().filter(value -> value.getEffectiveTo() == null && value.getEffectiveFrom().isBefore(request.effectiveFrom()))
                .forEach(value -> { value.setEffectiveTo(request.effectiveFrom().minusDays(1)); transportRepository.save(value); });

        StudentTransportFeeAssignment change = new StudentTransportFeeAssignment();
        change.setSchoolId(schoolId);
        change.setStudentId(student.getStudentId());
        change.setAcademicSession(request.academicSession());
        change.setEnabled(request.enabled());
        change.setDistance(request.enabled() ? request.distance() : null);
        change.setEffectiveFrom(request.effectiveFrom());
        change.setEffectiveTo(effectiveUntil);
        change.setReason(reason);
        change.setChangedBy(securityUtil.getUsername());
        transportRepository.save(change);

        List<Integer> months = generatedMonthsInRange(student.getStudentId(), request.academicSession(),
                request.effectiveFrom(), effectiveUntil);
        List<RecalculationEntryDto> recalculated = months.stream()
                .map(month -> recalculationService.recalculateOneWithTransport(student.getStudentId(), request.academicSession(),
                        month, request.enabled(), request.distance(), reason, ip))
                .toList();
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "CHANGE_STUDENT_TRANSPORT_FEES",
                "StudentTransportFeeAssignment", student.getStudentId(), null,
                "enabled=" + request.enabled() + ", effective=" + request.effectiveFrom(), ip);
        return new StudentRecalculationResult(student.getStudentId(), true, recalculated,
                months.isEmpty() ? "Transport saved; no existing generated months were affected." : null);
    }

    private StudentRecalculationResult applyDiscountForStudent(Student student, AcademicSession session,
                                                                FeeHead feeHead, BulkDiscountRequest request,
                                                                String ip) {
        Long schoolId = securityUtil.getSchoolId();
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session.getLabel());
        String suppliedReason = request.reason() == null ? "" : request.reason().trim();
        String auditReason = suppliedReason.isBlank()
                ? "Discount or waiver applied from fee assignment."
                : suppliedReason;
        List<Integer> selectedMonths = request.months().stream().distinct().sorted().toList();
        List<LocalDate> monthStarts = selectedMonths.stream()
                .map(month -> calculationService.academicMonthStart(month, years[0], years[1], startMonth))
                .toList();

        for (LocalDate selectedMonth : monthStarts) {
            LocalDate from = selectedMonth.withDayOfMonth(1);
            LocalDate until = from.withDayOfMonth(from.lengthOfMonth());
            if (feeConfigRepository.existsOverlapping(schoolId, student.getStudentId(), session.getId(), feeHead.getId(),
                    from, until)) {
                throw new IllegalArgumentException("A discount or waiver already exists for "
                        + from.getMonth() + " " + from.getYear() + ".");
            }
        }

        for (LocalDate selectedMonth : monthStarts) {
            LocalDate from = selectedMonth.withDayOfMonth(1);
            StudentFeeConfig config = new StudentFeeConfig();
            config.setSchoolId(schoolId);
            config.setStudentId(student.getStudentId());
            config.setAcademicSession(session);
            config.setFeeHead(feeHead);
            config.setConfigType(request.configType());
            config.setValue(request.value());
            config.setValidFrom(from);
            config.setValidUntil(from.withDayOfMonth(from.lengthOfMonth()));
            config.setReason(suppliedReason.isBlank() ? null : suppliedReason);
            config.setApprovedBy(securityUtil.getUsername());
            feeConfigRepository.save(config);
        }

        Set<Integer> generatedMonths = new HashSet<>(generatedMonthsInRange(
                student.getStudentId(), session.getLabel(), monthStarts.getFirst(), monthStarts.getLast()));
        List<Integer> monthsToRecalculate = selectedMonths.stream().filter(generatedMonths::contains).toList();
        List<RecalculationEntryDto> recalculated = monthsToRecalculate.stream()
                .map(month -> recalculationService.recalculateOne(student.getStudentId(), session.getLabel(), month,
                        auditReason, ip))
                .toList();
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "APPLY_BULK_STUDENT_DISCOUNT",
                "StudentFeeConfig", student.getStudentId(), null,
                "feeHead=" + feeHead.getId() + ", type=" + request.configType()
                        + ", months=" + selectedMonths, ip);
        return new StudentRecalculationResult(student.getStudentId(), true, recalculated,
                monthsToRecalculate.isEmpty()
                        ? "Discount saved for the selected months; no existing generated months were affected."
                        : null);
    }

    private List<Integer> generatedMonthsInRange(String studentId, String session, LocalDate from, LocalDate until) {
        Long schoolId = securityUtil.getSchoolId();
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session);
        return studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(studentId, schoolId, session).stream()
                .map(StudentFees::getMonth)
                .filter(Objects::nonNull)
                .filter(month -> {
                    LocalDate date = calculationService.academicMonthStart(month, years[0], years[1], startMonth);
                    return !date.withDayOfMonth(1).isBefore(from.withDayOfMonth(1))
                            && (until == null || !date.withDayOfMonth(1).isAfter(until.withDayOfMonth(1)));
                })
                .distinct().sorted().toList();
    }

    private WorkflowChangeResult summarizeChanges(int requested, List<StudentRecalculationResult> students) {
        int saved = (int) students.stream().filter(StudentRecalculationResult::changeSaved).count();
        int recalculated = students.stream().flatMap(value -> value.months().stream()).mapToInt(value -> value.isOk() ? 1 : 0).sum();
        int skipped = students.stream().flatMap(value -> value.months().stream()).mapToInt(value -> value.isOk() ? 0 : 1).sum();
        return new WorkflowChangeResult(requested, saved, recalculated, skipped, students);
    }

    private void validateBulkDiscount(BulkDiscountRequest request) {
        if (request == null || request.studentIds() == null || request.studentIds().isEmpty()
                || request.academicSessionId() == null || request.feeHeadId() == null
                || request.configType() == null || request.validFrom() == null
                || request.months() == null || request.months().isEmpty()) {
            throw new IllegalArgumentException("Students, session, fee head, type, selected months and start date are required.");
        }
        if (request.months().stream().anyMatch(month -> month == null || month < 1 || month > 12)) {
            throw new IllegalArgumentException("Selected academic months must be between 1 and 12.");
        }
        if (request.validUntil() != null && request.validUntil().isBefore(request.validFrom())) {
            throw new IllegalArgumentException("Discount end date cannot be before its start date.");
        }
        if (request.configType() == FeeConfigType.DISCOUNT_PERCENT
                && (request.value() == null || request.value().compareTo(BigDecimal.ZERO) < 0
                || request.value().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("Percentage discount must be between 0 and 100.");
        }
        if ((request.configType() == FeeConfigType.DISCOUNT_FIXED || request.configType() == FeeConfigType.CUSTOM_AMOUNT)
                && (request.value() == null || request.value().compareTo(BigDecimal.ZERO) < 0)) {
            throw new IllegalArgumentException("A non-negative amount is required.");
        }
    }

    private void validateDiscountUpdate(DiscountUpdateRequest request) {
        if (request == null || request.configType() == null || request.validFrom() == null)
            throw new IllegalArgumentException("Discount type and start month are required.");
        if (request.configType() == FeeConfigType.DISCOUNT_PERCENT
                && (request.value() == null || request.value().compareTo(BigDecimal.ZERO) < 0
                || request.value().compareTo(BigDecimal.valueOf(100)) > 0))
            throw new IllegalArgumentException("Percentage discount must be between 0 and 100.");
        if ((request.configType() == FeeConfigType.DISCOUNT_FIXED || request.configType() == FeeConfigType.CUSTOM_AMOUNT)
                && (request.value() == null || request.value().compareTo(BigDecimal.ZERO) < 0))
            throw new IllegalArgumentException("A non-negative amount is required.");
        requireReason(request.reason());
    }

    private DiscountHistoryRow toDiscountHistory(StudentFeeConfig value) {
        return new DiscountHistoryRow(value.getId(), value.getStudentId(), value.getFeeHead().getId(),
                value.getFeeHead().getName(), value.getConfigType(), value.getValue(), value.getValidFrom(),
                value.getValidUntil(), value.getReason(), value.getApprovedBy(), value.getCreatedAt(),
                value.getRevokedAt(), value.getRevokedBy(), value.getRevokeReason());
    }

    private TransportHistoryRow toTransportHistory(StudentTransportFeeAssignment value) {
        return new TransportHistoryRow(value.getId(), value.getStudentId(), value.isEnabled(), value.getDistance(),
                value.getEffectiveFrom(), value.getEffectiveTo(), value.getReason(), value.getChangedBy(), value.getCreatedAt());
    }

    private GenerationBatchRow toGenerationBatchRow(FeeGenerationBatch value) {
        return new GenerationBatchRow(value.getId(), value.getAcademicSession(), value.getEffectiveDate(),
                parseMonths(value.getSelectedMonths()), value.getRequestedStudents(), value.getSuccessfulStudents(),
                value.getFailedStudents(), value.getGeneratedMonths(), value.getSkippedMonths(), value.getStatus(),
                value.getInitiatedBy(), value.getRetryOfBatchId(), value.getStartedAt(), value.getCompletedAt(),
                parseCsv(value.getFailedStudentIds()));
    }

    private LocalDate monthStart(LocalDate value) { return value == null ? null : value.withDayOfMonth(1); }
    private LocalDate monthEnd(LocalDate value) { return value == null ? null : value.withDayOfMonth(value.lengthOfMonth()); }

    private StudentPreview previewStudent(Student student, String session, LocalDate requestedEffectiveDate,
                                          List<Integer> months, MidSessionFeePolicy requestPolicy,
                                          SchoolFeeSettings settings) {
        Long schoolId = securityUtil.getSchoolId();
        FeeCalculationService.FeeConfigurationStatus config = calculationService.validateFeeConfiguration(schoolId, session, student.getClassName());
        if (!config.valid()) return new StudentPreview(student.getStudentId(), student.getName(), false, BigDecimal.ZERO, List.of(), config.reason());
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session);
        Set<Long> charged = new HashSet<>(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, student.getStudentId()));
        List<MonthPreview> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean first = true;
        PolicyContext policy = resolvePolicyContext(student, session, requestedEffectiveDate, requestPolicy, settings);
        for (int month : months) {
            MonthDecision decision = monthDecision(month, session, policy, startMonth);
            if (!decision.eligible()) {
                rows.add(new MonthPreview(month, false, false, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, policy.effectiveDate(), null, decision.message()));
                continue;
            }
            StudentFees existing = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(student.getStudentId(), schoolId, session, month);
            if (existing != null) {
                rows.add(new MonthPreview(month, true, false, existing.getBaseAmountDue(), existing.getDiscountAmount(),
                        existing.getBusFeeDue(), safe(existing.getBaseAmountDue()).add(safe(existing.getBusFeeDue())),
                        policy.effectiveDate(), existing.getProrationFactor(), "Already generated"));
                continue;
            }
            LocalDate asOf = calculationService.academicMonthStart(month, years[0], years[1], startMonth);
            TransportState transport = transportState(student, session, asOf);
            FeeCalculationService.MonthSnapshot snapshot = calculationService.computeMonthSnapshot(schoolId, session,
                    student.getClassName(), student.getStudentId(), month, first, asOf, transport.enabled(), transport.distance(), charged);
            if (decision.prorated()) snapshot = calculationService.prorateRecurringSnapshot(snapshot, policy.effectiveDate());
            BigDecimal amount = safe(snapshot.baseAmountDue()).add(safe(snapshot.busFeeDue()));
            total = total.add(amount);
            rows.add(new MonthPreview(month, false, true, snapshot.baseAmountDue(), snapshot.discountAmount(), snapshot.busFeeDue(), amount,
                    policy.effectiveDate(), decision.prorated() ? calculationService.prorationFactor(policy.effectiveDate()) : BigDecimal.ONE,
                    decision.message()));
            charged.addAll(snapshot.newlyChargedOneTimeFeeHeadIds());
            first = false;
        }
        return new StudentPreview(student.getStudentId(), student.getName(), true, total, rows, null);
    }

    private GenerationResult generateForStudent(Student student, String session, LocalDate requestedEffectiveDate,
                                                List<Integer> months, MidSessionFeePolicy requestPolicy,
                                                SchoolFeeSettings settings) {
        Long schoolId = securityUtil.getSchoolId();
        StudentFeeAssignment assignment = assignmentRepository.findForGenerationUpdate(schoolId, student.getStudentId(), session)
                .orElse(null);
        if (assignment == null || assignment.isExcluded() || assignment.getStatus() == StudentFeeAssignmentStatus.NOT_ASSIGNED) {
            return new GenerationResult(student.getStudentId(), 0, months.size(), false, "Student is not assigned for fees.");
        }
        int generated = 0, skipped = 0;
        FeeCalculationService.FeeConfigurationStatus config = calculationService.validateFeeConfiguration(schoolId, session, student.getClassName());
        if (!config.valid()) throw new IllegalStateException(config.reason());
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session);
        PolicyContext policy = resolvePolicyContext(student, session, requestedEffectiveDate, requestPolicy, settings);
        Set<Long> charged = new HashSet<>(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, student.getStudentId()));
        boolean first = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(student.getStudentId(), schoolId, session).isEmpty();
        for (int month : months) {
            MonthDecision decision = monthDecision(month, session, policy, startMonth);
            if (!decision.eligible()) { skipped++; continue; }
            if (studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(student.getStudentId(), schoolId, session, month) != null) { skipped++; continue; }
            LocalDate asOf = calculationService.academicMonthStart(month, years[0], years[1], startMonth);
            TransportState transport = transportState(student, session, asOf);
            FeeCalculationService.MonthSnapshot snapshot = calculationService.computeMonthSnapshot(schoolId, session,
                    student.getClassName(), student.getStudentId(), month, first, asOf, transport.enabled(), transport.distance(), charged);
            if (decision.prorated()) snapshot = calculationService.prorateRecurringSnapshot(snapshot, policy.effectiveDate());
            StudentFees fee = new StudentFees();
            fee.setSchoolId(schoolId); fee.setStudentId(student.getStudentId()); fee.setClassName(student.getClassName());
            fee.setMonth(month); fee.setYear(session); fee.setPaid(false); fee.setTakesBus(transport.enabled());
            fee.setDistance(transport.distance() == null ? 0.0 : transport.distance()); fee.setManuallyPaid(false);
            fee.setBaseAmountDue(snapshot.baseAmountDue()); fee.setBusFeeDue(snapshot.busFeeDue());
            fee.setDiscountAmount(snapshot.discountAmount()); fee.setAmountComputedAt(LocalDateTime.now());
            fee.setAmountRuleSnapshot(snapshot.ruleSnapshotJson()); fee.setSnapshotStatus(snapshot.status());
            fee.setBillingEffectiveDate(policy.effectiveDate()); fee.setMidSessionFeePolicy(policy.policy());
            fee.setProrationFactor(decision.prorated() ? calculationService.prorationFactor(policy.effectiveDate()) : BigDecimal.ONE);
            studentFeesRepository.save(fee);
            for (FeeCalculationService.LineItemSnapshot li : snapshot.lineItems()) {
                StudentFeesLineItem item = new StudentFeesLineItem();
                item.setStudentFeesId(fee.getId()); item.setSchoolId(schoolId); item.setStudentId(student.getStudentId());
                item.setSession(session); item.setMonth(month); item.setLineItemType(LineItemType.valueOf(li.lineItemType()));
                item.setFeeHeadId(li.feeHeadId()); item.setFeeHeadCode(li.feeHeadCode()); item.setFeeHeadName(li.feeHeadName());
                item.setFrequency(li.frequency()); item.setGrossAmountPaise(li.grossPaise());
                item.setDiscountAmountPaise(li.discountPaise()); item.setNetAmountPaise(li.netPaise());
                item.setDiscountConfigType(li.discountConfigType()); lineItemRepository.save(item);
            }
            for (Long id : snapshot.newlyChargedOneTimeFeeHeadIds()) {
                if (!oneTimeRepository.existsBySchoolIdAndStudentIdAndFeeHeadId(schoolId, student.getStudentId(), id))
                    oneTimeRepository.save(new StudentOneTimeFeeCharged(schoolId, student.getStudentId(), id));
                charged.add(id);
            }
            generated++; first = false;
        }
        List<Integer> totalGeneratedMonths = studentFeesRepository
                .findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(student.getStudentId(), schoolId, session)
                .stream().map(StudentFees::getMonth).distinct().toList();
        assignment.setStatus(statusForMonths(parseMonths(assignment.getSelectedMonths()), totalGeneratedMonths, true));
        assignment.setGeneratedAt(LocalDateTime.now()); assignment.setFailureReason(null); assignmentRepository.save(assignment);
        return new GenerationResult(student.getStudentId(), generated, skipped, true, "Generation completed.");
    }

    private void markGenerationFailed(Long schoolId, String studentId, String session, RuntimeException ex) {
        assignmentRepository.findBySchoolIdAndStudentIdAndAcademicSession(schoolId, studentId, session).ifPresent(assignment -> {
            assignment.setStatus(StudentFeeAssignmentStatus.GENERATION_FAILED);
            assignment.setFailureReason(safeMessage(ex));
            assignmentRepository.save(assignment);
        });
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Fee generation failed." : ex.getMessage();
    }

    private TransportState transportState(Student student, String session, LocalDate date) {
        return transportRepository.effectiveOn(securityUtil.getSchoolId(), student.getStudentId(), session, date)
                .map(t -> new TransportState(t.isEnabled(), t.getDistance()))
                .orElse(new TransportState(Boolean.TRUE.equals(student.getTakesBus()), student.getDistance()));
    }

    private record TransportState(boolean enabled, Double distance) {}
    private record PolicyContext(LocalDate effectiveDate, MidSessionFeePolicy policy,
                                 LocalDate sessionStart, LocalDate sessionEnd, boolean midSession) {}
    private record MonthDecision(boolean eligible, boolean prorated, String message) {}

    private PolicyContext resolvePolicyContext(Student student, String session, LocalDate requestedEffectiveDate,
                                               MidSessionFeePolicy requestPolicy, SchoolFeeSettings settings) {
        Long schoolId = securityUtil.getSchoolId();
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session);
        LocalDate sessionStart = calculationService.academicMonthStart(1, years[0], years[1], startMonth);
        LocalDate sessionEnd = calculationService.academicMonthStart(12, years[0], years[1], startMonth).withDayOfMonth(
                calculationService.academicMonthStart(12, years[0], years[1], startMonth).lengthOfMonth());
        LocalDate effective = latest(requestedEffectiveDate, student.getJoiningDate(), settings.getActivationDate(), sessionStart);
        MidSessionFeePolicy policy = requestPolicy != null ? requestPolicy : settings.getMidSessionPolicy() != null
                ? settings.getMidSessionPolicy() : MidSessionFeePolicy.FROM_EFFECTIVE_MONTH;
        return new PolicyContext(effective, policy, sessionStart, sessionEnd, effective.isAfter(sessionStart));
    }

    private MonthDecision monthDecision(int month, String session, PolicyContext context, int startMonth) {
        int[] years = calculationService.parseSession(session);
        LocalDate monthStart = calculationService.academicMonthStart(month, years[0], years[1], startMonth);
        if (context.effectiveDate().isAfter(context.sessionEnd())) {
            return new MonthDecision(false, false, "Effective billing date is after this academic session.");
        }
        LocalDate eligibleStart = context.midSession() && context.policy() == MidSessionFeePolicy.NEXT_MONTH
                ? context.effectiveDate().withDayOfMonth(1).plusMonths(1)
                : context.effectiveDate().withDayOfMonth(1);
        if (monthStart.isBefore(eligibleStart)) {
            String reason = context.policy() == MidSessionFeePolicy.NEXT_MONTH
                    ? "Skipped by next-month admission policy."
                    : "Before the student's effective billing month.";
            return new MonthDecision(false, false, reason);
        }
        boolean prorated = context.midSession() && context.policy() == MidSessionFeePolicy.PRORATE_JOINING_MONTH
                && monthStart.getYear() == context.effectiveDate().getYear()
                && monthStart.getMonth() == context.effectiveDate().getMonth()
                && context.effectiveDate().getDayOfMonth() > 1;
        return new MonthDecision(true, prorated, prorated
                ? "Recurring monthly and transport charges are prorated from " + context.effectiveDate() + "."
                : null);
    }

    private LocalDate latest(LocalDate... values) {
        return Arrays.stream(values).filter(Objects::nonNull).max(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("An effective billing date is required."));
    }
    private LocalDate sessionStart(String session, Long schoolId) {
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session);
        return calculationService.academicMonthStart(1, years[0], years[1], startMonth);
    }
    private long count(List<AssignmentRow> rows, StudentFeeAssignmentStatus status) { return rows.stream().filter(r -> r.status() == status).count(); }
    private StudentFeeAssignmentStatus resolveAssignmentStatus(StudentFeeAssignment assignment,
                                                               List<Integer> generatedMonths) {
        if (assignment != null && (assignment.isExcluded()
                || assignment.getStatus() == StudentFeeAssignmentStatus.EXCLUDED)) {
            return StudentFeeAssignmentStatus.EXCLUDED;
        }
        if (assignment != null && assignment.getStatus() == StudentFeeAssignmentStatus.GENERATION_FAILED) {
            return StudentFeeAssignmentStatus.GENERATION_FAILED;
        }
        List<Integer> assignedMonths = assignment == null ? List.of() : parseMonths(assignment.getSelectedMonths());
        return statusForMonths(assignedMonths, generatedMonths, assignment != null);
    }
    private StudentFeeAssignmentStatus statusForMonths(List<Integer> assignedMonths,
                                                       List<Integer> generatedMonths,
                                                       boolean assigned) {
        Set<Integer> generated = new HashSet<>(generatedMonths);
        if (generated.isEmpty()) {
            return assigned ? StudentFeeAssignmentStatus.READY
                    : StudentFeeAssignmentStatus.NOT_ASSIGNED;
        }
        // The visible status represents completion of the full academic session,
        // not merely completion of the months selected in the latest request.
        return generated.size() >= 12 ? StudentFeeAssignmentStatus.GENERATED
                : StudentFeeAssignmentStatus.PARTIALLY_GENERATED;
    }
    private StudentFeeAssignmentStatus deriveStatus(String studentId, Long schoolId, String session) {
        int count = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(studentId, schoolId, session).size();
        return count == 0 ? StudentFeeAssignmentStatus.NOT_ASSIGNED : count == 12 ? StudentFeeAssignmentStatus.GENERATED : StudentFeeAssignmentStatus.PARTIALLY_GENERATED;
    }
    private List<Student> requireStudents(Long schoolId, List<String> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("At least one student is required.");
        List<Student> students = studentRepository.findByStudentIdInAndSchoolId(ids.stream().distinct().toList(), schoolId);
        if (students.size() != ids.stream().distinct().count()) throw new IllegalArgumentException("One or more students were not found in this school.");
        return students;
    }
    private void validateAssignmentRequest(AssignmentRequest request) {
        if (request == null || request.studentIds() == null || request.studentIds().isEmpty() || request.effectiveDate() == null)
            throw new IllegalArgumentException("Students and effective date are required.");
        validateSession(request.academicSession()); normalizeMonths(request.months());
    }
    private void validateSession(String session) { calculationService.parseSession(session); }
    private List<Integer> normalizeMonths(List<Integer> months) {
        if (months == null || months.isEmpty()) throw new IllegalArgumentException("Select at least one academic month.");
        List<Integer> result = months.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (result.isEmpty() || result.stream().anyMatch(m -> m < 1 || m > 12)) throw new IllegalArgumentException("Months must be between 1 and 12.");
        return result;
    }
    private String joinMonths(List<Integer> months) { return months.stream().map(String::valueOf).collect(Collectors.joining(",")); }
    private List<Integer> parseMonths(String value) { return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(Integer::valueOf).toList(); }
    private List<String> parseCsv(String value) { return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList(); }
    private String requireReason(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("A reason is required."); return value.trim(); }
    private String firstNonBlank(String a, String b) { return a != null && !a.isBlank() ? a : b; }
    private BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
