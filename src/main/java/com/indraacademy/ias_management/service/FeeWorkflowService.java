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
            return settingsRepository.save(settings);
        });
    }

    @Transactional
    public SchoolFeeSettings updateSettings(SettingsUpdate request, String ip) {
        if (request == null || request.operationalStatus() == null) {
            throw new IllegalArgumentException("Operational status is required.");
        }
        SchoolFeeSettings settings = getSettings();
        FeeOperationalStatus old = settings.getOperationalStatus();
        if (request.operationalStatus() == FeeOperationalStatus.ACTIVE && request.activationDate() == null
                && settings.getActivationDate() == null) {
            throw new IllegalArgumentException("An activation date is required before fees can be activated.");
        }
        settings.setOperationalStatus(request.operationalStatus());
        if (request.activationDate() != null) settings.setActivationDate(request.activationDate());
        if (request.midSessionPolicy() != null) settings.setMidSessionPolicy(request.midSessionPolicy());
        if (request.allowRetroactiveGeneration() != null) settings.setAllowRetroactiveGeneration(request.allowRetroactiveGeneration());
        if (request.automaticAnnualGeneration() != null) settings.setAutomaticAnnualGeneration(request.automaticAnnualGeneration());
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
        Map<String, StudentFeeAssignment> assignments = assignmentRepository.findBySchoolIdAndAcademicSession(schoolId, session)
                .stream().collect(Collectors.toMap(StudentFeeAssignment::getStudentId, Function.identity()));
        List<AssignmentRow> rows = new ArrayList<>();
        for (Student student : students) {
            StudentFeeAssignment assignment = assignments.get(student.getStudentId());
            StudentFeeAssignmentStatus resolved = assignment != null ? assignment.getStatus() : deriveStatus(student.getStudentId(), schoolId, session);
            if (status != null && resolved != status) continue;
            long generated = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(
                    student.getStudentId(), schoolId, session).size();
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
        List<StudentFeeAssignment> saved = new ArrayList<>();
        for (Student student : students) {
            StudentFeeAssignment assignment = assignmentRepository
                    .findBySchoolIdAndStudentIdAndAcademicSession(schoolId, student.getStudentId(), request.academicSession())
                    .orElseGet(StudentFeeAssignment::new);
            assignment.setSchoolId(schoolId);
            assignment.setStudentId(student.getStudentId());
            assignment.setAcademicSession(request.academicSession());
            assignment.setEffectiveDate(request.effectiveDate());
            assignment.setSelectedMonths(joinMonths(months));
            assignment.setExcluded(excluded);
            assignment.setExclusionReason(excluded ? requireReason(request.reason()) : null);
            assignment.setFailureReason(null);
            assignment.setStatus(excluded ? StudentFeeAssignmentStatus.EXCLUDED : StudentFeeAssignmentStatus.READY);
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
        return students.stream().map(s -> previewStudent(s, request.academicSession(), months)).toList();
    }

    public List<GenerationResult> generate(AssignmentRequest request, String ip) {
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
        List<GenerationResult> results = new ArrayList<>();
        for (Student student : students) {
            try {
                GenerationResult result = transactionTemplate.execute(status ->
                        generateForStudent(student, request.academicSession(), months));
                results.add(Objects.requireNonNull(result));
            } catch (RuntimeException ex) {
                transactionTemplate.executeWithoutResult(status ->
                        markGenerationFailed(schoolId, student.getStudentId(), request.academicSession(), ex));
                results.add(new GenerationResult(student.getStudentId(), 0, 0, false, safeMessage(ex)));
            }
        }
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "GENERATE_ASSIGNED_STUDENT_FEES",
                "StudentFees", request.academicSession(), null, request.studentIds() + ", months=" + months, ip);
        return results;
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
        if (feeConfigRepository.existsOverlapping(schoolId, student.getStudentId(), session.getId(), feeHead.getId(),
                request.validFrom(), request.validUntil())) {
            throw new IllegalArgumentException("An overlapping discount already exists.");
        }
        StudentFeeConfig config = new StudentFeeConfig();
        config.setSchoolId(schoolId);
        config.setStudentId(student.getStudentId());
        config.setAcademicSession(session);
        config.setFeeHead(feeHead);
        config.setConfigType(request.configType());
        config.setValue(request.value());
        config.setValidFrom(request.validFrom());
        config.setValidUntil(request.validUntil());
        config.setReason(request.reason().trim());
        config.setApprovedBy(securityUtil.getUsername());
        feeConfigRepository.save(config);

        List<Integer> months = generatedMonthsInRange(student.getStudentId(), session.getLabel(),
                request.validFrom(), request.validUntil());
        List<RecalculationEntryDto> recalculated = months.stream()
                .map(month -> recalculationService.recalculateOne(student.getStudentId(), session.getLabel(), month,
                        request.reason(), ip))
                .toList();
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "APPLY_BULK_STUDENT_DISCOUNT",
                "StudentFeeConfig", student.getStudentId(), null,
                "feeHead=" + feeHead.getId() + ", type=" + request.configType(), ip);
        return new StudentRecalculationResult(student.getStudentId(), true, recalculated,
                months.isEmpty() ? "Discount saved; no existing generated months were affected." : null);
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
                || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("Students, session, fee head, type, start date and reason are required.");
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

    private StudentPreview previewStudent(Student student, String session, List<Integer> months) {
        Long schoolId = securityUtil.getSchoolId();
        FeeCalculationService.FeeConfigurationStatus config = calculationService.validateFeeConfiguration(schoolId, session, student.getClassName());
        if (!config.valid()) return new StudentPreview(student.getStudentId(), student.getName(), false, BigDecimal.ZERO, List.of(), config.reason());
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session);
        Set<Long> charged = new HashSet<>(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, student.getStudentId()));
        List<MonthPreview> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean first = true;
        for (int month : months) {
            StudentFees existing = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(student.getStudentId(), schoolId, session, month);
            if (existing != null) {
                rows.add(new MonthPreview(month, true, false, existing.getBaseAmountDue(), existing.getDiscountAmount(),
                        existing.getBusFeeDue(), safe(existing.getBaseAmountDue()).add(safe(existing.getBusFeeDue())), "Already generated"));
                continue;
            }
            LocalDate asOf = calculationService.academicMonthStart(month, years[0], years[1], startMonth);
            TransportState transport = transportState(student, session, asOf);
            FeeCalculationService.MonthSnapshot snapshot = calculationService.computeMonthSnapshot(schoolId, session,
                    student.getClassName(), student.getStudentId(), month, first, asOf, transport.enabled(), transport.distance(), charged);
            BigDecimal amount = safe(snapshot.baseAmountDue()).add(safe(snapshot.busFeeDue()));
            total = total.add(amount);
            rows.add(new MonthPreview(month, false, true, snapshot.baseAmountDue(), snapshot.discountAmount(), snapshot.busFeeDue(), amount, null));
            charged.addAll(snapshot.newlyChargedOneTimeFeeHeadIds());
            first = false;
        }
        return new StudentPreview(student.getStudentId(), student.getName(), true, total, rows, null);
    }

    private GenerationResult generateForStudent(Student student, String session, List<Integer> months) {
        Long schoolId = securityUtil.getSchoolId();
        StudentFeeAssignment assignment = assignmentRepository.findBySchoolIdAndStudentIdAndAcademicSession(schoolId, student.getStudentId(), session)
                .orElse(null);
        if (assignment == null || assignment.isExcluded() || assignment.getStatus() == StudentFeeAssignmentStatus.NOT_ASSIGNED) {
            return new GenerationResult(student.getStudentId(), 0, months.size(), false, "Student is not assigned for fees.");
        }
        assignment.setStatus(StudentFeeAssignmentStatus.GENERATING);
        assignmentRepository.save(assignment);
        int generated = 0, skipped = 0;
        FeeCalculationService.FeeConfigurationStatus config = calculationService.validateFeeConfiguration(schoolId, session, student.getClassName());
        if (!config.valid()) throw new IllegalStateException(config.reason());
        int startMonth = schoolRepository.findById(schoolId).map(School::getAcademicYearStartMonth).orElse(4);
        int[] years = calculationService.parseSession(session);
        Set<Long> charged = new HashSet<>(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, student.getStudentId()));
        boolean first = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(student.getStudentId(), schoolId, session).isEmpty();
        for (int month : months) {
            if (studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(student.getStudentId(), schoolId, session, month) != null) { skipped++; continue; }
            LocalDate asOf = calculationService.academicMonthStart(month, years[0], years[1], startMonth);
            TransportState transport = transportState(student, session, asOf);
            FeeCalculationService.MonthSnapshot snapshot = calculationService.computeMonthSnapshot(schoolId, session,
                    student.getClassName(), student.getStudentId(), month, first, asOf, transport.enabled(), transport.distance(), charged);
            StudentFees fee = new StudentFees();
            fee.setSchoolId(schoolId); fee.setStudentId(student.getStudentId()); fee.setClassName(student.getClassName());
            fee.setMonth(month); fee.setYear(session); fee.setPaid(false); fee.setTakesBus(transport.enabled());
            fee.setDistance(transport.distance() == null ? 0.0 : transport.distance()); fee.setManuallyPaid(false);
            fee.setBaseAmountDue(snapshot.baseAmountDue()); fee.setBusFeeDue(snapshot.busFeeDue());
            fee.setDiscountAmount(snapshot.discountAmount()); fee.setAmountComputedAt(LocalDateTime.now());
            fee.setAmountRuleSnapshot(snapshot.ruleSnapshotJson()); fee.setSnapshotStatus(snapshot.status());
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
        int totalGeneratedMonths = studentFeesRepository
                .findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(student.getStudentId(), schoolId, session).size();
        assignment.setStatus(totalGeneratedMonths >= 12
                ? StudentFeeAssignmentStatus.GENERATED
                : StudentFeeAssignmentStatus.PARTIALLY_GENERATED);
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
    private long count(List<AssignmentRow> rows, StudentFeeAssignmentStatus status) { return rows.stream().filter(r -> r.status() == status).count(); }
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
    private String requireReason(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("A reason is required."); return value.trim(); }
    private String firstNonBlank(String a, String b) { return a != null && !a.isBlank() ? a : b; }
    private BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
