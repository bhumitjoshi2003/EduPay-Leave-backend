package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.*;
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
import java.util.stream.Collectors;

/**
 * E5B: fee generation for a target AcademicSession driven entirely by authoritative
 * StudentEnrollment rows — never Student.className, never determineNextClass(), never a
 * synthesized academic-year label. A candidate is a student with exactly one PLANNED or
 * ACTIVE enrollment in the target session; a CANCELLED or CLOSED row is never a candidate
 * and never produces fees. This is a separate, admin-triggered workflow — it does not touch
 * SchoolFeeSettings.automaticAnnualGeneration or any scheduler.
 */
@Service
public class FeeGenerationTargetService {
    private final AcademicSessionRepository academicSessionRepository;
    private final AcademicSessionService academicSessionService;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final SchoolClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final FeeCalculationService calculationService;
    private final StudentFeesRepository studentFeesRepository;
    private final StudentFeesLineItemRepository lineItemRepository;
    private final StudentOneTimeFeeChargedRepository oneTimeRepository;
    private final StudentTransportFeeAssignmentRepository transportRepository;
    private final AuditService auditService;
    private final SecurityUtil securityUtil;
    private final TransactionTemplate transactionTemplate;

    public FeeGenerationTargetService(AcademicSessionRepository academicSessionRepository,
                                       AcademicSessionService academicSessionService,
                                       StudentEnrollmentRepository enrollmentRepository,
                                       StudentRepository studentRepository,
                                       SchoolClassRepository classRepository,
                                       SectionRepository sectionRepository,
                                       FeeCalculationService calculationService,
                                       StudentFeesRepository studentFeesRepository,
                                       StudentFeesLineItemRepository lineItemRepository,
                                       StudentOneTimeFeeChargedRepository oneTimeRepository,
                                       StudentTransportFeeAssignmentRepository transportRepository,
                                       AuditService auditService,
                                       SecurityUtil securityUtil,
                                       PlatformTransactionManager transactionManager) {
        this.academicSessionRepository = academicSessionRepository;
        this.academicSessionService = academicSessionService;
        this.enrollmentRepository = enrollmentRepository;
        this.studentRepository = studentRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.calculationService = calculationService;
        this.studentFeesRepository = studentFeesRepository;
        this.lineItemRepository = lineItemRepository;
        this.oneTimeRepository = oneTimeRepository;
        this.transportRepository = transportRepository;
        this.auditService = auditService;
        this.securityUtil = securityUtil;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private static final List<StudentEnrollmentStatus> ELIGIBLE_STATUSES =
            List.of(StudentEnrollmentStatus.PLANNED, StudentEnrollmentStatus.ACTIVE);
    private static final List<Integer> EXPECTED_TARGET_MONTHS =
            java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList();

    @Transactional(readOnly = true)
    public List<StudentPreviewRow> preview(Long targetSessionId, Long classId, String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession targetSession = requireSession(schoolId, targetSessionId);
        List<StudentEnrollment> candidates = candidateEnrollments(schoolId, targetSessionId, classId, studentId);
        List<StudentPreviewRow> rows = new ArrayList<>();
        for (StudentEnrollment enrollment : candidates) {
            rows.add(previewOne(schoolId, targetSession, enrollment));
        }
        return rows;
    }

    /**
     * E5C read-only drift report. There is deliberately no repair/recalculation call here.
     * StudentFees has no trustworthy generator/provenance field, so a fee-only student with
     * no enrollment row at all in this target session is omitted: classId may have been added
     * by the legacy class-id backfill and is not proof that E5B created the row. Once target-
     * session enrollment history exists, that history is authoritative enough to compare.
     */
    @Transactional(readOnly = true)
    public List<TargetDriftRow> targetDrift(Long targetSessionId, Long classId, String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession session = requireSession(schoolId, targetSessionId);
        List<StudentFees> fees = studentFeesRepository.findBySchoolIdAndYear(schoolId, session.getLabel());
        if (studentId != null && !studentId.isBlank()) {
            fees = fees.stream().filter(f -> studentId.equals(f.getStudentId())).toList();
        }
        Map<String, List<StudentFees>> byStudent = fees.stream()
                .collect(Collectors.groupingBy(StudentFees::getStudentId, LinkedHashMap::new, Collectors.toList()));
        List<TargetDriftRow> result = new ArrayList<>();
        for (Map.Entry<String, List<StudentFees>> entry : byStudent.entrySet()) {
            List<StudentEnrollment> history = enrollmentRepository
                    .findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                            schoolId, entry.getKey(), targetSessionId);
            if (history.isEmpty()) continue; // provenance is unknowable; do not label legacy data
            TargetDriftRow row = driftOne(schoolId, session, history, entry.getValue());
            boolean matchesClass = classId == null
                    || Objects.equals(classId, row.enrollmentClassId())
                    || row.generatedClassIds().contains(classId);
            if (matchesClass) result.add(row);
        }
        result.sort(Comparator.comparing(TargetDriftRow::studentName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(TargetDriftRow::studentId));
        return result;
    }

    private TargetDriftRow driftOne(Long schoolId, AcademicSession session,
                                    List<StudentEnrollment> history, List<StudentFees> fees) {
        String studentId = fees.getFirst().getStudentId();
        String studentName = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .map(Student::getName).orElse(null);
        List<StudentEnrollment> applicable = history.stream()
                .filter(e -> ELIGIBLE_STATUSES.contains(e.getStatus())).toList();
        StudentEnrollment authoritative = applicable.size() == 1 ? applicable.getFirst() : null;
        StudentEnrollment cancelled = history.stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.CANCELLED)
                .max(Comparator.comparing(StudentEnrollment::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);

        List<Integer> generatedMonths = fees.stream().map(StudentFees::getMonth)
                .filter(Objects::nonNull).distinct().sorted().toList();
        List<Integer> missingMonths = EXPECTED_TARGET_MONTHS.stream()
                .filter(m -> !generatedMonths.contains(m)).toList();
        List<Long> generatedClassIds = fees.stream().map(StudentFees::getClassId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        List<String> generatedClassNames = fees.stream().map(StudentFees::getClassName)
                .filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        Long generatedClassId = generatedClassIds.size() == 1 ? generatedClassIds.getFirst() : null;
        String generatedClassName = generatedClassNames.size() == 1 ? generatedClassNames.getFirst() : null;
        List<String> warnings = new ArrayList<>();

        DriftStatus status;
        StudentEnrollment displayEnrollment = authoritative;
        if (authoritative == null && cancelled != null && applicable.isEmpty()) {
            status = DriftStatus.CANCELLED_TARGET_WITH_FEES;
            displayEnrollment = cancelled;
            warnings.add("The target enrollment was cancelled after target-session fee rows existed.");
        } else if (authoritative == null) {
            status = DriftStatus.NO_TARGET_ENROLLMENT;
            warnings.add(applicable.size() > 1
                    ? "Multiple applicable target enrollments exist; no authoritative row can be selected safely."
                    : "Target-session enrollment history exists, but no PLANNED or ACTIVE enrollment is applicable.");
        } else {
            boolean idMismatch = generatedClassIds.isEmpty()
                    || generatedClassIds.size() != 1
                    || !Objects.equals(generatedClassId, authoritative.getClassId());
            boolean snapshotMismatch = generatedClassNames.size() != 1
                    || !Objects.equals(generatedClassName, authoritative.getClassNameSnapshot());
            if (idMismatch || snapshotMismatch) {
                status = DriftStatus.CLASS_MISMATCH;
                warnings.add("Generated fee class evidence differs from the authoritative target enrollment.");
            } else if (!missingMonths.isEmpty()) {
                status = DriftStatus.PARTIAL_GENERATION;
                warnings.add("Only some of the 12 target-session academic months have generated fees.");
            } else {
                status = DriftStatus.CLEAN;
            }
        }
        if (generatedMonths.stream().anyMatch(m -> m < 1 || m > 12)) {
            warnings.add("Fee rows contain month values outside the canonical academic-month range 1-12.");
        }
        if (generatedClassIds.size() > 1 || generatedClassNames.size() > 1) {
            warnings.add("Generated fee rows contain more than one class snapshot.");
        }
        return new TargetDriftRow(studentId, studentName, session.getId(), session.getLabel(),
                displayEnrollment == null ? null : displayEnrollment.getId(),
                displayEnrollment == null ? null : displayEnrollment.getStatus().name(),
                displayEnrollment == null ? null : displayEnrollment.getClassId(),
                displayEnrollment == null ? null : displayEnrollment.getClassNameSnapshot(),
                generatedClassId, generatedClassName, generatedClassIds, generatedClassNames,
                generatedMonths, EXPECTED_TARGET_MONTHS, missingMonths, status, warnings);
    }

    /** Exactly one non-CANCELLED, non-CLOSED (PLANNED or ACTIVE) enrollment per student in the
     *  target session. A student with zero or more-than-one such row is never a candidate — a
     *  corrupt/ambiguous shape must never be guessed at for a financial input. */
    private List<StudentEnrollment> candidateEnrollments(Long schoolId, Long targetSessionId, Long classId, String studentId) {
        List<StudentEnrollment> all;
        if (studentId != null) {
            all = enrollmentRepository.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(schoolId, studentId, targetSessionId);
        } else if (classId != null) {
            all = enrollmentRepository.findBySchoolIdAndAcademicSessionIdAndClassIdOrderByEffectiveFromAsc(schoolId, targetSessionId, classId);
        } else {
            all = enrollmentRepository.findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(schoolId, targetSessionId);
        }
        Map<String, List<StudentEnrollment>> byStudent = all.stream()
                .filter(e -> ELIGIBLE_STATUSES.contains(e.getStatus()))
                .collect(Collectors.groupingBy(StudentEnrollment::getStudentId, LinkedHashMap::new, Collectors.toList()));
        List<StudentEnrollment> result = new ArrayList<>();
        for (List<StudentEnrollment> rows : byStudent.values()) {
            if (rows.size() == 1) result.add(rows.getFirst());
        }
        return result;
    }

    private StudentPreviewRow previewOne(Long schoolId, AcademicSession targetSession, StudentEnrollment enrollment) {
        String studentId = enrollment.getStudentId();
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
        List<String> warnings = new ArrayList<>();
        List<String> blockingErrors = new ArrayList<>();
        if (student == null) {
            blockingErrors.add("Student record not found.");
            return new StudentPreviewRow(studentId, null, enrollment.getId(), enrollment.getStatus().name(),
                    enrollment.getClassId(), enrollment.getClassNameSnapshot(), enrollment.getSectionId(),
                    enrollment.getSectionNameSnapshot(), null, BigDecimal.ZERO, List.of(), List.of(), warnings, blockingErrors, false);
        }
        Optional<SchoolClass> targetClass = classRepository.findByIdAndSchoolId(enrollment.getClassId(), schoolId);
        if (targetClass.isEmpty()) {
            blockingErrors.add("Target class no longer exists.");
            return new StudentPreviewRow(studentId, student.getName(), enrollment.getId(), enrollment.getStatus().name(),
                    enrollment.getClassId(), enrollment.getClassNameSnapshot(), enrollment.getSectionId(),
                    enrollment.getSectionNameSnapshot(), null, BigDecimal.ZERO, List.of(), List.of(), warnings, blockingErrors, false);
        }
        String className = targetClass.get().getName();
        String targetSectionName = enrollment.getSectionId() == null ? null
                : sectionRepository.findByIdAndSchoolId(enrollment.getSectionId(), schoolId).map(Section::getName).orElse(null);
        Boolean repeatingSameClass = repeatingSameClass(schoolId, enrollment);

        if (enrollment.getStatus() == StudentEnrollmentStatus.PLANNED) {
            warnings.add("This enrollment is recorded for the upcoming session and is not yet active.");
        }

        FeeCalculationService.FeeConfigurationStatus config =
                calculationService.validateFeeConfiguration(schoolId, targetSession.getLabel(), className);
        if (!config.valid()) {
            blockingErrors.add(config.reason());
            return new StudentPreviewRow(studentId, student.getName(), enrollment.getId(), enrollment.getStatus().name(),
                    enrollment.getClassId(), className, enrollment.getSectionId(), targetSectionName,
                    repeatingSameClass, BigDecimal.ZERO, List.of(), List.of(), warnings, blockingErrors, false);
        }

        Set<Long> charged = new HashSet<>(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, studentId));
        boolean first = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(studentId, schoolId, targetSession.getLabel()).isEmpty();
        boolean anyMonthMissingTransportAssignment = false;
        List<MonthPreview> months = new ArrayList<>();
        List<Integer> alreadyGenerated = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            LocalDate asOf = academicSessionService.academicMonthToDate(targetSession, month);
            StudentFees existing = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(studentId, schoolId, targetSession.getLabel(), month);
            if (existing != null) {
                alreadyGenerated.add(month);
                BigDecimal amount = safe(existing.getBaseAmountDue()).add(safe(existing.getBusFeeDue()));
                months.add(new MonthPreview(month, true, existing.getBaseAmountDue(), existing.getDiscountAmount(),
                        existing.getBusFeeDue(), amount, "Already generated"));
                continue;
            }
            boolean hasAssignment = transportRepository.effectiveOn(schoolId, studentId, targetSession.getLabel(), asOf).isPresent();
            if (!hasAssignment) anyMonthMissingTransportAssignment = true;
            TransportState transport = transportState(student, targetSession.getLabel(), asOf);
            FeeCalculationService.MonthSnapshot snapshot = calculationService.computeMonthSnapshot(schoolId, targetSession.getLabel(),
                    className, studentId, month, first, asOf, transport.enabled(), transport.distance(), charged);
            BigDecimal amount = safe(snapshot.baseAmountDue()).add(safe(snapshot.busFeeDue()));
            total = total.add(amount);
            months.add(new MonthPreview(month, false, snapshot.baseAmountDue(), snapshot.discountAmount(),
                    snapshot.busFeeDue(), amount, null));
            charged.addAll(snapshot.newlyChargedOneTimeFeeHeadIds());
            first = false;
        }
        if (anyMonthMissingTransportAssignment) {
            warnings.add("No transport assignment recorded for the target session; current transport status will be used.");
        }
        return new StudentPreviewRow(studentId, student.getName(), enrollment.getId(), enrollment.getStatus().name(),
                enrollment.getClassId(), className, enrollment.getSectionId(), targetSectionName,
                repeatingSameClass, total, months, alreadyGenerated, warnings, blockingErrors, true);
    }

    /** Best-effort "repeating class" indicator: compares the target class against the student's
     *  most recent non-CANCELLED segment before this one, across any session. Purely
     *  informational — never a validation gate — so it degrades to null (not determinable)
     *  rather than guessing when there's no prior segment. */
    private Boolean repeatingSameClass(Long schoolId, StudentEnrollment target) {
        List<StudentEnrollment> history = enrollmentRepository
                .findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(schoolId, target.getStudentId());
        StudentEnrollment mostRecentPrior = null;
        for (StudentEnrollment e : history) {
            if (Objects.equals(e.getId(), target.getId())) continue;
            if (e.getStatus() == StudentEnrollmentStatus.CANCELLED) continue;
            if (!e.getEffectiveFrom().isBefore(target.getEffectiveFrom())) continue;
            if (mostRecentPrior == null || e.getEffectiveFrom().isAfter(mostRecentPrior.getEffectiveFrom())) {
                mostRecentPrior = e;
            }
        }
        return mostRecentPrior == null ? null : Objects.equals(mostRecentPrior.getClassId(), target.getClassId());
    }

    public List<StudentGenerationResult> generate(GenerationRequest request, String ip) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession targetSession = requireSession(schoolId, request.targetSessionId());
        List<StudentGenerationResult> results = new ArrayList<>();
        for (GenerationDecision decision : request.decisions()) {
            try {
                StudentGenerationResult result = transactionTemplate.execute(status ->
                        generateForStudent(schoolId, targetSession, decision));
                results.add(Objects.requireNonNull(result));
            } catch (RuntimeException ex) {
                results.add(new StudentGenerationResult(decision.studentId(), GenerationOutcome.FAILED, 0, 0, safeMessage(ex)));
            }
        }
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "GENERATE_TARGET_ENROLLMENT_FEES",
                "StudentFees", targetSession.getLabel(), null,
                request.decisions().stream().map(GenerationDecision::studentId).toList().toString(), ip);
        return results;
    }

    /** Runs in its own REQUIRES_NEW-equivalent transaction (a fresh transaction via
     *  TransactionTemplate, since this loop body itself is not @Transactional) so one
     *  student's failure never rolls back another's. Re-locks and re-reads the authoritative
     *  enrollment before writing anything: if it no longer matches what the preview promised
     *  (corrected, cancelled, activated into a different class, or gone) this returns
     *  ENROLLMENT_CHANGED without generating a single row — never a silent auto-repair. */
    private StudentGenerationResult generateForStudent(Long schoolId, AcademicSession targetSession, GenerationDecision decision) {
        String studentId = decision.studentId();
        List<StudentEnrollment> history = enrollmentRepository.findAllHistoryForUpdate(schoolId, studentId);
        StudentEnrollment enrollment = history.stream()
                .filter(e -> Objects.equals(e.getId(), decision.expectedTargetEnrollmentId()))
                .findFirst().orElse(null);
        if (enrollment == null) {
            return new StudentGenerationResult(studentId, GenerationOutcome.ENROLLMENT_CHANGED, 0, 0,
                    "Target enrollment no longer exists.");
        }
        if (!ELIGIBLE_STATUSES.contains(enrollment.getStatus())) {
            return new StudentGenerationResult(studentId, GenerationOutcome.ENROLLMENT_CHANGED, 0, 0,
                    "Target enrollment is no longer PLANNED or ACTIVE (now " + enrollment.getStatus() + ").");
        }
        if (!Objects.equals(enrollment.getClassId(), decision.expectedTargetClassId())) {
            return new StudentGenerationResult(studentId, GenerationOutcome.ENROLLMENT_CHANGED, 0, 0,
                    "Target class has changed since preview.");
        }
        Optional<SchoolClass> targetClass = classRepository.findByIdAndSchoolId(enrollment.getClassId(), schoolId);
        if (targetClass.isEmpty()) {
            return new StudentGenerationResult(studentId, GenerationOutcome.ENROLLMENT_CHANGED, 0, 0,
                    "Target class no longer exists.");
        }
        String className = targetClass.get().getName();
        FeeCalculationService.FeeConfigurationStatus config =
                calculationService.validateFeeConfiguration(schoolId, targetSession.getLabel(), className);
        if (!config.valid()) {
            return new StudentGenerationResult(studentId, GenerationOutcome.NO_RULE_CONFIGURED, 0, 0, config.reason());
        }
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
        if (student == null) {
            return new StudentGenerationResult(studentId, GenerationOutcome.FAILED, 0, 0, "Student record not found.");
        }

        Set<Long> charged = new HashSet<>(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, studentId));
        boolean first = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(studentId, schoolId, targetSession.getLabel()).isEmpty();
        int generated = 0, skipped = 0;
        for (int month = 1; month <= 12; month++) {
            if (studentFeesRepository.findByStudentIdAndSchoolIdAndAcademicSessionIdAndMonth(studentId, schoolId, targetSession.getId(), month) != null) {
                skipped++; continue;
            }
            LocalDate asOf = academicSessionService.academicMonthToDate(targetSession, month);
            TransportState transport = transportState(student, targetSession.getLabel(), asOf);
            FeeCalculationService.MonthSnapshot snapshot = calculationService.computeMonthSnapshot(schoolId, targetSession.getLabel(),
                    className, studentId, month, first, asOf, transport.enabled(), transport.distance(), charged);
            StudentFees fee = new StudentFees();
            fee.setSchoolId(schoolId); fee.setStudentId(studentId); fee.setClassId(enrollment.getClassId());
            fee.setClassName(className); fee.setMonth(month); fee.setYear(targetSession.getLabel());
            fee.setAcademicSessionId(targetSession.getId());
            fee.setPaid(false); fee.setTakesBus(transport.enabled());
            fee.setDistance(transport.distance() == null ? 0.0 : transport.distance()); fee.setManuallyPaid(false);
            fee.setBaseAmountDue(snapshot.baseAmountDue()); fee.setBusFeeDue(snapshot.busFeeDue());
            fee.setDiscountAmount(snapshot.discountAmount()); fee.setAmountComputedAt(LocalDateTime.now());
            fee.setAmountRuleSnapshot(snapshot.ruleSnapshotJson()); fee.setSnapshotStatus(snapshot.status());
            fee.setProrationFactor(BigDecimal.ONE);
            studentFeesRepository.save(fee);
            for (FeeCalculationService.LineItemSnapshot li : snapshot.lineItems()) {
                StudentFeesLineItem item = new StudentFeesLineItem();
                item.setStudentFeesId(fee.getId()); item.setSchoolId(schoolId); item.setStudentId(studentId);
                item.setSession(targetSession.getLabel()); item.setAcademicSessionId(targetSession.getId());
                item.setMonth(month); item.setLineItemType(LineItemType.valueOf(li.lineItemType()));
                item.setFeeHeadId(li.feeHeadId()); item.setFeeHeadCode(li.feeHeadCode()); item.setFeeHeadName(li.feeHeadName());
                item.setFrequency(li.frequency()); item.setGrossAmountPaise(li.grossPaise());
                item.setDiscountAmountPaise(li.discountPaise()); item.setNetAmountPaise(li.netPaise());
                item.setDiscountConfigType(li.discountConfigType()); lineItemRepository.save(item);
            }
            for (Long id : snapshot.newlyChargedOneTimeFeeHeadIds()) {
                if (!oneTimeRepository.existsBySchoolIdAndStudentIdAndFeeHeadId(schoolId, studentId, id)) {
                    oneTimeRepository.save(new StudentOneTimeFeeCharged(schoolId, studentId, id));
                }
                charged.add(id);
            }
            generated++; first = false;
        }
        GenerationOutcome outcome = generated == 0 && skipped == 12 ? GenerationOutcome.ALREADY_GENERATED
                : skipped == 0 ? GenerationOutcome.GENERATED
                : GenerationOutcome.PARTIALLY_GENERATED;
        return new StudentGenerationResult(studentId, outcome, generated, skipped,
                outcome == GenerationOutcome.ALREADY_GENERATED ? "All months already generated." : "Generation completed.");
    }

    private AcademicSession requireSession(Long schoolId, Long sessionId) {
        return academicSessionRepository.findByIdAndSchoolId(sessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("AcademicSession not found for this school."));
    }

    private TransportState transportState(Student student, String session, LocalDate date) {
        return transportRepository.effectiveOn(securityUtil.getSchoolId(), student.getStudentId(), session, date)
                .map(t -> new TransportState(t.isEnabled(), t.getDistance()))
                .orElse(new TransportState(Boolean.TRUE.equals(student.getTakesBus()), student.getDistance()));
    }

    private record TransportState(boolean enabled, Double distance) {}

    private BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Fee generation failed." : ex.getMessage();
    }
}
