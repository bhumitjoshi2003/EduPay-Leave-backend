package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.RecalculationEntryDto;
import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.entity.StudentOneTimeFeeCharged;
import com.indraacademy.ias_management.repository.AllocationRefundRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentOneTimeFeeChargedRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 5A — explicit, admin-only, audited recalculation of a single StudentFees row's
 * snapshot (and its StudentFeesLineItem breakdown), using the exact same canonical pipeline
 * generation uses (FeeCalculationService.computeMonthSnapshot). Recalculation is NEVER
 * triggered automatically by editing FeeStructureRule/FeeHead/bus pricing/StudentFeeConfig —
 * it only ever runs when an admin explicitly invokes {@link #recalculateOne} with a reason.
 * <p>
 * <b>Safety invariant</b>: only a row with zero financial activity (never allocated to, never
 * refunded/reversed against, not paid/manually-paid, no recorded amountPaid) is eligible —
 * see {@link #ineligibilityReason}. Recalculation never touches Payment,
 * PaymentStudentFeesAllocation, Refund, or AllocationRefund; it changes the bill, never
 * historical money movement.
 * <p>
 * <b>isFirstRow replay</b>: whether a row was originally generated as a student's very first
 * row for the session (appliesAtJoin's mid-session catch-up billing) changes fee-head
 * eligibility for ANNUAL/QUARTERLY/SEMI_ANNUAL/ONE_TIME heads. There is no stored flag for
 * this, so it is re-derived as "this row is the earliest month present for this
 * student+session" (see {@link #computeIsFirstRow}) — provably correct because
 * appliesAtJoin(1, head) and appliesThisAcademicMonth(1, head) are always identical (so a
 * continuing student's month-1 row, misclassified as isFirstRow=true by this heuristic,
 * still computes the same result either way), and only a genuine mid-session joiner's rows
 * start later than month 1 in the first place.
 * <p>
 * <b>ONE_TIME dedup replay</b>: StudentOneTimeFeeCharged has no per-row linkage, so
 * recalculating the exact row that originally triggered a ONE_TIME charge must not treat
 * that charge as "already charged elsewhere" (which would silently drop it) — see
 * {@link #buildAlreadyChargedOneTimeFeeHeadIds}, which excludes fee heads already present in
 * this row's own current active line items from the dedup set before calling
 * computeMonthSnapshot.
 */
@Service
public class StudentFeesRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesRecalculationService.class);

    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Autowired private StudentOneTimeFeeChargedRepository studentOneTimeFeeChargedRepository;
    @Autowired private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Autowired private AllocationRefundRepository allocationRefundRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    /**
     * Preview: computes what each requested month's snapshot WOULD become under the current
     * configuration, without writing anything — no row is locked, no field is mutated, no
     * line item is touched. Ineligible/not-found months are reported (ok=false, message set)
     * alongside eligible ones so the caller can show why a given month can't be recalculated.
     */
    @Transactional(readOnly = true)
    public List<RecalculationEntryDto> preview(String studentId, String session, List<Integer> months) {
        Long schoolId = securityUtil.getSchoolId();
        List<RecalculationEntryDto> results = new ArrayList<>();
        for (Integer month : months) {
            RecalculationEntryDto dto = new RecalculationEntryDto();
            dto.setMonth(month);
            if (month == null || month < 1 || month > 12) {
                dto.setOk(false);
                dto.setMessage("Invalid month.");
                results.add(dto);
                continue;
            }
            StudentFees fee = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(studentId, schoolId, session, month);
            if (fee == null) {
                dto.setOk(false);
                dto.setMessage("No StudentFees row found for this student/session/month.");
                results.add(dto);
                continue;
            }
            populateOld(dto, fee);
            String ineligible = ineligibilityReason(fee);
            if (ineligible != null) {
                dto.setOk(false);
                dto.setMessage(ineligible);
                results.add(dto);
                continue;
            }
            FeeCalculationService.FeeConfigurationStatus configStatus =
                    feeCalculationService.validateFeeConfiguration(schoolId, session, fee.getClassName());
            if (!configStatus.valid()) {
                dto.setOk(false);
                dto.setMessage("No valid fee configuration: " + configStatus.reason());
                results.add(dto);
                continue;
            }
            FeeCalculationService.MonthSnapshot snapshot = computeSnapshotFor(fee, schoolId, session, month);
            populateNew(dto, snapshot);
            dto.setOk(true);
            results.add(dto);
        }
        return results;
    }

    /**
     * Apply: locks the row (PESSIMISTIC_WRITE, serializing against any concurrent payment or
     * refund touching the same row), re-validates eligibility against the freshly-locked
     * state (never trusts a prior Preview call), recomputes via the same canonical pipeline,
     * and atomically replaces the snapshot fields and the active line-item set — all inside
     * one transaction, so a failure partway through rolls back both together. Writes exactly
     * one audit entry on success; a rejection is logged (log.warn) but not audited, since
     * nothing changed.
     */
    @Transactional
    public RecalculationEntryDto recalculateOne(String studentId, String session, Integer month, String reason, String ip) {
        String actorUsername = securityUtil.getUsername();
        String actorRole = securityUtil.getRole();
        RecalculationEntryDto dto = new RecalculationEntryDto();
        dto.setMonth(month);

        if (reason == null || reason.isBlank()) {
            dto.setOk(false);
            dto.setMessage("A reason is required to recalculate.");
            return dto;
        }
        if (month == null || month < 1 || month > 12) {
            dto.setOk(false);
            dto.setMessage("Invalid month.");
            return dto;
        }

        Long schoolId = securityUtil.getSchoolId();
        // schoolId-scoped lookup IS the tenant-ownership check — a cross-school request
        // simply finds no row, exactly like every other endpoint in this module.
        StudentFees fee = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(studentId, schoolId, session, month);
        if (fee == null) {
            dto.setOk(false);
            dto.setMessage("No StudentFees row found for this student/session/month.");
            return dto;
        }
        populateOld(dto, fee);

        String ineligible = ineligibilityReason(fee);
        if (ineligible != null) {
            log.warn("Recalculation rejected for StudentFees id={} (student={}, session={}, month={}): {}",
                    fee.getId(), studentId, session, month, ineligible);
            dto.setOk(false);
            dto.setMessage(ineligible);
            return dto;
        }

        FeeCalculationService.FeeConfigurationStatus configStatus =
                feeCalculationService.validateFeeConfiguration(schoolId, session, fee.getClassName());
        if (!configStatus.valid()) {
            dto.setOk(false);
            dto.setMessage("No valid fee configuration: " + configStatus.reason());
            return dto;
        }

        BigDecimal oldBase = fee.getBaseAmountDue();
        BigDecimal oldBus = fee.getBusFeeDue();
        BigDecimal oldDiscount = fee.getDiscountAmount();
        String oldRuleSnapshot = fee.getAmountRuleSnapshot();
        SnapshotStatus oldStatus = fee.getSnapshotStatus();
        LocalDateTime oldComputedAt = fee.getAmountComputedAt();

        FeeCalculationService.MonthSnapshot snapshot = computeSnapshotFor(fee, schoolId, session, month);

        fee.setBaseAmountDue(snapshot.baseAmountDue());
        fee.setBusFeeDue(snapshot.busFeeDue());
        fee.setDiscountAmount(snapshot.discountAmount());
        fee.setAmountComputedAt(LocalDateTime.now());
        fee.setAmountRuleSnapshot(snapshot.ruleSnapshotJson());
        fee.setSnapshotStatus(snapshot.status());
        studentFeesRepository.save(fee);

        replaceLineItems(fee, schoolId, studentId, session, month, snapshot);

        for (Long feeHeadId : snapshot.newlyChargedOneTimeFeeHeadIds()) {
            if (!studentOneTimeFeeChargedRepository.existsBySchoolIdAndStudentIdAndFeeHeadId(schoolId, studentId, feeHeadId)) {
                studentOneTimeFeeChargedRepository.save(new StudentOneTimeFeeCharged(schoolId, studentId, feeHeadId));
            }
        }

        populateNew(dto, snapshot);
        dto.setOk(true);

        auditRecalculation(fee, schoolId, session, month, reason, oldBase, oldBus, oldDiscount,
                oldRuleSnapshot, oldStatus, oldComputedAt, snapshot, actorUsername, actorRole, ip);

        log.info("Recalculated StudentFees id={} (student={}, session={}, month={}) by {} ({}): base {} -> {}, bus {} -> {}, discount {} -> {}",
                fee.getId(), studentId, session, month, actorUsername, actorRole,
                oldBase, snapshot.baseAmountDue(), oldBus, snapshot.busFeeDue(), oldDiscount, snapshot.discountAmount());

        return dto;
    }

    /**
     * Marks every currently-active line item for this row as superseded (never deleted, see
     * StudentFeesLineItem's javadoc), then inserts the new snapshot's line items as the new
     * active set — mirroring exactly what generation writes, just replacing rather than
     * creating fresh. Both halves run in the same @Transactional method as the StudentFees
     * row update above, so a failure here rolls back the snapshot-field change too.
     * <p>
     * The explicit {@code flush()} between the two loops is load-bearing, not defensive
     * styling: Hibernate's automatic dirty-checking flush orders ALL pending inserts before
     * ALL pending updates, regardless of the order save() was called in application code.
     * Without forcing the supersede UPDATEs to hit the database first, the new line item's
     * INSERT for a fee head that already has an active row (e.g. recalculating the same fee
     * head twice) collides with uq_sfli_studentfees_feehead_active before the old row's
     * supersededAt has actually been written — confirmed via a real Postgres constraint
     * violation in end-to-end testing (Mockito-mocked repository tests never exercise real
     * flush ordering and could not have caught this).
     */
    private void replaceLineItems(StudentFees fee, Long schoolId, String studentId, String session, Integer month,
                                   FeeCalculationService.MonthSnapshot snapshot) {
        List<StudentFeesLineItem> activeLineItems =
                studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId());
        LocalDateTime supersededAt = LocalDateTime.now();
        for (StudentFeesLineItem li : activeLineItems) {
            li.setSupersededAt(supersededAt);
            studentFeesLineItemRepository.save(li);
        }
        studentFeesLineItemRepository.flush();

        for (FeeCalculationService.LineItemSnapshot li : snapshot.lineItems()) {
            StudentFeesLineItem newLineItem = new StudentFeesLineItem();
            newLineItem.setStudentFeesId(fee.getId());
            newLineItem.setSchoolId(schoolId);
            newLineItem.setStudentId(studentId);
            newLineItem.setSession(session);
            newLineItem.setMonth(month);
            newLineItem.setLineItemType(LineItemType.valueOf(li.lineItemType()));
            newLineItem.setFeeHeadId(li.feeHeadId());
            newLineItem.setFeeHeadCode(li.feeHeadCode());
            newLineItem.setFeeHeadName(li.feeHeadName());
            newLineItem.setFrequency(li.frequency());
            newLineItem.setGrossAmountPaise(li.grossPaise());
            newLineItem.setDiscountAmountPaise(li.discountPaise());
            newLineItem.setNetAmountPaise(li.netPaise());
            newLineItem.setDiscountConfigType(li.discountConfigType());
            studentFeesLineItemRepository.save(newLineItem);
        }
    }

    /**
     * The single computation shared by Preview and Apply — same inputs produce the same
     * output either way, which is what makes "Apply recomputes rather than trusting Preview"
     * true by construction rather than by convention.
     */
    private FeeCalculationService.MonthSnapshot computeSnapshotFor(StudentFees fee, Long schoolId, String session, Integer month) {
        School school = schoolRepository.findById(schoolId).orElse(null);
        int startMonth = school != null ? school.getAcademicYearStartMonth() : 4;
        int[] years = feeCalculationService.parseSession(session);
        LocalDate asOfDate = feeCalculationService.academicMonthStart(month, years[0], years[1], startMonth);
        boolean isFirstRow = computeIsFirstRow(fee, schoolId, session);

        List<StudentFeesLineItem> currentActive =
                studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId());
        Set<Long> alreadyChargedOneTimeFeeHeadIds = buildAlreadyChargedOneTimeFeeHeadIds(fee, schoolId, currentActive);

        return feeCalculationService.computeMonthSnapshot(
                schoolId, session, fee.getClassName(), fee.getStudentId(), month, isFirstRow, asOfDate,
                fee.getTakesBus(), fee.getDistance(), alreadyChargedOneTimeFeeHeadIds);
    }

    /** True iff this row's month is the earliest month present for this student+session —
     * see the class javadoc for why this is a safe, non-guessing stand-in for a stored
     * "was this the join row" flag. */
    private boolean computeIsFirstRow(StudentFees fee, Long schoolId, String session) {
        List<StudentFees> siblings = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(fee.getStudentId(), schoolId, session);
        if (siblings.isEmpty() || fee.getMonth() == null) return false;
        int minMonth = siblings.stream()
                .filter(sf -> sf.getMonth() != null)
                .mapToInt(StudentFees::getMonth)
                .min()
                .orElse(fee.getMonth());
        return fee.getMonth() == minMonth;
    }

    /** The dedup set to pass into computeMonthSnapshot: every ONE_TIME fee head ever charged
     * to this student, MINUS whichever ones this exact row is already the legitimate holder
     * of (present in its own current active line items) — see the class javadoc. Without the
     * subtraction, recalculating the very row that originally charged a ONE_TIME fee head
     * would cause computeMonthSnapshot to treat it as "already charged elsewhere" and silently
     * drop it. */
    private Set<Long> buildAlreadyChargedOneTimeFeeHeadIds(StudentFees fee, Long schoolId, List<StudentFeesLineItem> currentActiveLineItems) {
        Set<Long> dedup = new HashSet<>(studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, fee.getStudentId()));
        Set<Long> chargedByThisRow = currentActiveLineItems.stream()
                .filter(li -> li.getLineItemType() == LineItemType.FEE_HEAD && li.getFeeHeadId() != null)
                .map(StudentFeesLineItem::getFeeHeadId)
                .collect(Collectors.toSet());
        dedup.removeAll(chargedByThisRow);
        return dedup;
    }

    /**
     * Only a row with zero financial activity may be recalculated — checked against the
     * ledger FIRST (gross allocation and gross refund/reversal history, never just the
     * current net, so a fully-refunded-back-to-zero row is still rejected, per the explicit
     * "reject rather than guess" instruction), then against the row's own derived
     * paid/manuallyPaid/amountPaid fields as a second, independent signal — needed because a
     * historical manually-recorded payment predating the allocation ledger has no allocation
     * rows at all, so the ledger check alone would wrongly call it eligible. Returns null
     * when eligible, otherwise a human-readable rejection reason.
     */
    private String ineligibilityReason(StudentFees fee) {
        if (fee.getPaid() == null) {
            return "Row's paid status is unknown — refusing to guess; not eligible for recalculation.";
        }
        if (Boolean.TRUE.equals(fee.getPaid())) {
            return "Row is marked paid — recalculation would change a bill money has already been collected against.";
        }
        if (fee.getManuallyPaid() == null) {
            return "Row's manuallyPaid status is unknown — refusing to guess; not eligible for recalculation.";
        }
        if (Boolean.TRUE.equals(fee.getManuallyPaid())) {
            return "Row has a manual payment recorded.";
        }
        if (fee.getAmountPaid() != null && fee.getAmountPaid().signum() != 0) {
            return "Row has a non-zero recorded amountPaid.";
        }
        long grossAllocated = paymentAllocationRepository.sumAmountPaiseByStudentFeesId(fee.getId());
        if (grossAllocated != 0) {
            return "Row has payment allocation history (this month was paid against at some point, even if since refunded).";
        }
        long grossReversed = allocationRefundRepository.sumAmountPaiseByStudentFeesId(fee.getId());
        if (grossReversed != 0) {
            return "Row has refund/reversal history.";
        }
        return null;
    }

    private void populateOld(RecalculationEntryDto dto, StudentFees fee) {
        dto.setOldBaseAmountDue(fee.getBaseAmountDue());
        dto.setOldBusFeeDue(fee.getBusFeeDue());
        dto.setOldDiscountAmount(fee.getDiscountAmount());
        dto.setOldTotalDue(totalDue(fee.getBaseAmountDue(), fee.getBusFeeDue()));
    }

    private void populateNew(RecalculationEntryDto dto, FeeCalculationService.MonthSnapshot snapshot) {
        dto.setNewBaseAmountDue(snapshot.baseAmountDue());
        dto.setNewBusFeeDue(snapshot.busFeeDue());
        dto.setNewDiscountAmount(snapshot.discountAmount());
        dto.setNewTotalDue(totalDue(snapshot.baseAmountDue(), snapshot.busFeeDue()));
    }

    /** totalDue = baseAmountDue + busFeeDue — baseAmountDue is already net of discount (see
     * FeeCalculationService.resolveSchoolFeeDue's javadoc); never subtract discountAmount
     * from it again here. */
    private BigDecimal totalDue(BigDecimal base, BigDecimal bus) {
        BigDecimal b = base != null ? base : BigDecimal.ZERO;
        BigDecimal u = bus != null ? bus : BigDecimal.ZERO;
        return b.add(u);
    }

    private void auditRecalculation(StudentFees fee, Long schoolId, String session, Integer month, String reason,
                                     BigDecimal oldBase, BigDecimal oldBus, BigDecimal oldDiscount, String oldRuleSnapshot,
                                     SnapshotStatus oldStatus, LocalDateTime oldComputedAt,
                                     FeeCalculationService.MonthSnapshot newSnapshot,
                                     String actorUsername, String actorRole, String ip) {
        try {
            Map<String, Object> oldMap = new LinkedHashMap<>();
            oldMap.put("baseAmountDue", oldBase);
            oldMap.put("busFeeDue", oldBus);
            oldMap.put("discountAmount", oldDiscount);
            oldMap.put("totalDue", totalDue(oldBase, oldBus));
            oldMap.put("snapshotStatus", oldStatus != null ? oldStatus.name() : null);
            oldMap.put("amountComputedAt", oldComputedAt != null ? oldComputedAt.toString() : null);
            oldMap.put("amountRuleSnapshot", oldRuleSnapshot);

            Map<String, Object> newMap = new LinkedHashMap<>();
            newMap.put("studentId", fee.getStudentId());
            newMap.put("schoolId", schoolId);
            newMap.put("session", session);
            newMap.put("month", month);
            newMap.put("baseAmountDue", newSnapshot.baseAmountDue());
            newMap.put("busFeeDue", newSnapshot.busFeeDue());
            newMap.put("discountAmount", newSnapshot.discountAmount());
            newMap.put("totalDue", totalDue(newSnapshot.baseAmountDue(), newSnapshot.busFeeDue()));
            newMap.put("snapshotStatus", newSnapshot.status() != null ? newSnapshot.status().name() : null);
            newMap.put("amountComputedAt", LocalDateTime.now().toString());
            newMap.put("amountRuleSnapshot", newSnapshot.ruleSnapshotJson());
            newMap.put("reason", reason);

            auditService.log(actorUsername, actorRole, "RECALCULATE_STUDENT_FEES", "StudentFees",
                    fee.getId().toString(),
                    objectMapper.writeValueAsString(oldMap),
                    objectMapper.writeValueAsString(newMap),
                    ip);
        } catch (JsonProcessingException e) {
            log.error("Could not serialize recalculation audit entry for StudentFees id={}: {}", fee.getId(), e.getMessage());
        }
    }
}
