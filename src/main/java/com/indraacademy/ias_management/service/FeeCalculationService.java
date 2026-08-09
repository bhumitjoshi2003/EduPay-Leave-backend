package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeFrequency;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.FeeStructureRule;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for "how much does a class/session owe for a given academic
 * month" under the dynamic FeeHead + FeeStructureRule system — the fee-head-driven
 * replacement for the legacy fixed-column FeeStructure entity, which the admin fee-
 * structure UI (fee-structure.component.ts) stopped writing to some time ago.
 *
 * Extracted from StudentFeesService (formerly private calculateDynamicMonthFeeRupees /
 * feeAppliesThisAcademicMonth) so FeeReminderService can use the exact same proven logic
 * instead of a fourth independent reimplementation — this scheduling rule was already
 * drifting across StudentFeesService, InvoiceGenerationService, and the frontend's
 * fees.component.ts before this extraction; consolidating at least these two stops that.
 *
 * Callers still choosing between this and the legacy FeeStructure entity (see
 * FeeReminderService.getOverdueStudents) should treat this as authoritative when it has
 * any active rules for the class/session — the admin's real, currently-maintained fee
 * configuration lives here, not in the legacy table.
 */
@Service
public class FeeCalculationService {

    private static final Logger log = LoggerFactory.getLogger(FeeCalculationService.class);

    @Autowired private FeeStructureRuleRepository feeStructureRuleRepository;
    @Autowired private AcademicSessionRepository academicSessionRepository;
    @Autowired private ObjectMapper objectMapper;

    /**
     * Active dynamic fee rules for a class, as of today. Empty (never null) if the academic
     * session doesn't exist yet or no fee heads have been configured for this class/session —
     * callers should treat an empty list as "no dynamic configuration," not "zero fees."
     */
    public List<FeeStructureRule> loadActiveRules(Long schoolId, String session, String className) {
        Optional<AcademicSession> sessionOpt = academicSessionRepository.findBySchoolIdAndLabel(schoolId, session);
        if (sessionOpt.isEmpty()) {
            log.warn("Academic session '{}' not found for schoolId={}.", session, schoolId);
            return Collections.emptyList();
        }
        return feeStructureRuleRepository.findActiveRules(schoolId, sessionOpt.get().getId(), className, LocalDate.now());
    }

    /** Total fee (rupees) for one academic month, summed across every active fee head
     * that applies to that month. Does NOT include bus fees — those are a separate,
     * distance-based lookup callers add on top (see FeeReminderService/StudentFeesService). */
    public double calculateMonthFeeRupees(int academicMonth, List<FeeStructureRule> rules) {
        double total = 0;
        for (FeeStructureRule rule : rules) {
            FeeHead head = rule.getFeeHead();
            if (head == null || !head.isActive()) continue;
            if (appliesThisAcademicMonth(academicMonth, head)) {
                total += rule.getAmount() / 100.0; // paise → rupees
            }
        }
        return total;
    }

    /** Whether a fee head is due in the given academic month — from its explicit dueMonths
     * JSON, or (if left at the all-12-months admin-form default) derived from its frequency. */
    public boolean appliesThisAcademicMonth(int academicMonth, FeeHead head) {
        List<Integer> dueMonths;
        try {
            dueMonths = objectMapper.readValue(head.getDueMonths(), new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            log.warn("Could not parse dueMonths for fee head id={}: {}", head.getId(), head.getDueMonths());
            return false;
        }
        if (dueMonths.size() == 12) {
            FeeFrequency freq = head.getFrequency();
            if (freq == null) return false;
            return switch (freq) {
                case MONTHLY -> true;
                case QUARTERLY -> academicMonth % 3 == 1;    // months 1, 4, 7, 10
                case SEMI_ANNUAL -> academicMonth == 1 || academicMonth == 7;
                case ANNUAL, ONE_TIME -> academicMonth == 1;
            };
        }
        return dueMonths.contains(academicMonth);
    }
}
