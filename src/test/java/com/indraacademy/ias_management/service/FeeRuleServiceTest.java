package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeStructureRuleDto;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.FeeStructureRule;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeHeadRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Sub-phase 1a: saveRulesForClass moved from hard delete-then-insert to close-out-then-
 * insert, which is a prerequisite for asOf-dated lookups (generation, backfill, admin
 * recalculation) to ever resolve a historical rule correctly — deleting the old row would
 * make "what was active on this past date" unanswerable. These tests assert the new
 * behavior directly against the repository calls, since there's no H2/Testcontainers
 * dependency yet (tracked separately, see the plan's Phase 8) to run this against a real DB.
 */
@ExtendWith(MockitoExtension.class)
class FeeRuleServiceTest {

    @Mock private FeeStructureRuleRepository ruleRepository;
    @Mock private FeeHeadRepository feeHeadRepository;
    @Mock private AcademicSessionRepository sessionRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private FeeRuleService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final String CLASS_NAME = "5A";

    @BeforeEach
    void setUp() {
        service = new FeeRuleService();
        ReflectionTestUtils.setField(service, "ruleRepository", ruleRepository);
        ReflectionTestUtils.setField(service, "feeHeadRepository", feeHeadRepository);
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");

        AcademicSession session = new AcademicSession();
        session.setId(SESSION_ID);
        session.setSchoolId(SCHOOL_ID);
        session.setLabel("2025-2026");
        lenient().when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        // SchoolClass is authoritative — saveRulesForClass now requires it to exist before
        // touching any FeeStructureRule row. Stubbed to exist by default so the pre-existing
        // rule-versioning tests below (which predate this validation) keep exercising the
        // behavior they were written for; the rejection path is covered by its own tests.
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.setId(500L);
        schoolClass.setSchoolId(SCHOOL_ID);
        schoolClass.setName(CLASS_NAME);
        schoolClass.setActive(true);
        lenient().when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, CLASS_NAME))
                .thenReturn(Optional.of(schoolClass));

        lenient().when(ruleRepository.saveAndFlush(any(FeeStructureRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ruleRepository.save(any(FeeStructureRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private FeeHead feeHead(Long id) {
        FeeHead head = new FeeHead();
        head.setId(id);
        head.setName("Tuition");
        head.setCode("TUITION");
        return head;
    }

    private FeeStructureRule existingOpenRule(Long feeHeadId, long amount, LocalDate effectiveFrom) {
        AcademicSession session = new AcademicSession();
        session.setId(SESSION_ID);
        session.setLabel("2025-2026");

        FeeStructureRule rule = new FeeStructureRule();
        rule.setId(99L);
        rule.setSchoolId(SCHOOL_ID);
        rule.setFeeHead(feeHead(feeHeadId));
        rule.setAcademicSession(session);
        rule.setClassName(CLASS_NAME);
        rule.setAmount(amount);
        rule.setEffectiveFrom(effectiveFrom);
        rule.setEffectiveUntil(null); // open-ended
        return rule;
    }

    @Test
    void changedPriceClosesOutTheOldRuleInsteadOfDeletingIt() {
        FeeStructureRule existing = existingOpenRule(1L, 150_000L, LocalDate.of(2025, 4, 1));
        when(ruleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(SCHOOL_ID, SESSION_ID, CLASS_NAME))
                .thenReturn(List.of(existing));
        when(feeHeadRepository.findByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(feeHead(1L)));

        FeeStructureRuleDto incoming = new FeeStructureRuleDto();
        incoming.setFeeHeadId(1L);
        incoming.setAmount(200_000L); // price change
        incoming.setEffectiveFrom(LocalDate.of(2025, 9, 1));

        service.saveRulesForClass(SESSION_ID, CLASS_NAME, List.of(incoming), request);

        // The old rule must never be deleted...
        verify(ruleRepository, never()).delete(any());
        verify(ruleRepository, never()).deleteBySchoolIdAndAcademicSessionIdAndClassName(anyLong(), anyLong(), any());

        // ...it must be closed out (effectiveUntil set, just before the new rule starts)
        // and flushed before the new row is inserted.
        assertThat(existing.getEffectiveUntil()).isEqualTo(LocalDate.of(2025, 8, 31));
        verify(ruleRepository).saveAndFlush(existing);

        // And a genuinely new row must be inserted for the new price.
        ArgumentCaptor<FeeStructureRule> newRuleCaptor = ArgumentCaptor.forClass(FeeStructureRule.class);
        verify(ruleRepository, atLeastOnce()).save(newRuleCaptor.capture());
        assertThat(newRuleCaptor.getAllValues())
                .anySatisfy(r -> {
                    assertThat(r.getAmount()).isEqualTo(200_000L);
                    assertThat(r.getEffectiveUntil()).isNull();
                });
    }

    @Test
    void unchangedRuleIsLeftAsIsNotClosedOutOrDuplicated() {
        LocalDate from = LocalDate.of(2025, 4, 1);
        FeeStructureRule existing = existingOpenRule(1L, 150_000L, from);
        when(ruleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(SCHOOL_ID, SESSION_ID, CLASS_NAME))
                .thenReturn(List.of(existing));

        FeeStructureRuleDto incoming = new FeeStructureRuleDto();
        incoming.setFeeHeadId(1L);
        incoming.setAmount(150_000L); // same price
        incoming.setEffectiveFrom(from); // same date

        service.saveRulesForClass(SESSION_ID, CLASS_NAME, List.of(incoming), request);

        verify(ruleRepository, never()).saveAndFlush(any());
        verify(ruleRepository, never()).save(any());
        verify(feeHeadRepository, never()).findByIdAndSchoolId(anyLong(), anyLong());
        assertThat(existing.getEffectiveUntil()).isNull(); // still open
    }

    @Test
    void feeHeadDroppedFromPayloadIsClosedOutNotDeleted() {
        FeeStructureRule existing = existingOpenRule(1L, 150_000L, LocalDate.of(2025, 4, 1));
        when(ruleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(SCHOOL_ID, SESSION_ID, CLASS_NAME))
                .thenReturn(List.of(existing));

        // Incoming payload no longer includes fee head 1 at all.
        service.saveRulesForClass(SESSION_ID, CLASS_NAME, List.of(), request);

        verify(ruleRepository, never()).delete(any());
        verify(ruleRepository).save(existing);
        assertThat(existing.getEffectiveUntil()).isEqualTo(LocalDate.now());
    }

    @Test
    void historicalAsOfLookupCanStillResolveTheClosedOutRuleAfterAnEdit() {
        // This is the actual point of the fix: an asOf-dated lookup for a date before the
        // price change must still find the OLD rule, which is only possible if it still
        // exists (closed out, not deleted).
        LocalDate oldRuleStart = LocalDate.of(2025, 4, 1);
        FeeStructureRule existing = existingOpenRule(1L, 150_000L, oldRuleStart);
        when(ruleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(SCHOOL_ID, SESSION_ID, CLASS_NAME))
                .thenReturn(List.of(existing));
        when(feeHeadRepository.findByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(feeHead(1L)));

        FeeStructureRuleDto incoming = new FeeStructureRuleDto();
        incoming.setFeeHeadId(1L);
        incoming.setAmount(200_000L);
        incoming.setEffectiveFrom(LocalDate.of(2025, 9, 1));

        service.saveRulesForClass(SESSION_ID, CLASS_NAME, List.of(incoming), request);

        // The old row (id=99) is still present in the repository's state, now closed out —
        // an asOf lookup for e.g. 2025-06-01 would resolve to it via the unchanged
        // effectiveFrom <= asOf <= effectiveUntil predicate in FeeStructureRuleRepository.
        assertThat(existing.getEffectiveFrom()).isEqualTo(oldRuleStart);
        assertThat(existing.getEffectiveUntil()).isEqualTo(LocalDate.of(2025, 8, 31));
        assertThat(existing.getEffectiveFrom()).isBeforeOrEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(existing.getEffectiveUntil()).isAfterOrEqualTo(LocalDate.of(2025, 6, 1));
    }

    // ─── SchoolClass validation (E2E audit fix) ────────────────────────────────
    // The E2E test created fee_structure_rule rows with class_name='10' while zero
    // school_class rows existed for that school at all. These tests lock in that this
    // can no longer happen: SchoolClass is authoritative and must exist first.

    @Test
    void feeRuleForConfiguredClassSucceeds() {
        // setUp() already stubs schoolClassRepository to resolve CLASS_NAME ("5A").
        when(ruleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(SCHOOL_ID, SESSION_ID, CLASS_NAME))
                .thenReturn(List.of());
        when(feeHeadRepository.findByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(feeHead(1L)));

        FeeStructureRuleDto incoming = new FeeStructureRuleDto();
        incoming.setFeeHeadId(1L);
        incoming.setAmount(100_000L);
        incoming.setEffectiveFrom(LocalDate.of(2025, 4, 1));

        List<FeeStructureRuleDto> result = service.saveRulesForClass(SESSION_ID, CLASS_NAME, List.of(incoming), request);

        assertThat(result).hasSize(1);
        ArgumentCaptor<FeeStructureRule> captor = ArgumentCaptor.forClass(FeeStructureRule.class);
        verify(ruleRepository).save(captor.capture());
        assertThat(captor.getValue().getClassId()).isEqualTo(500L);
        assertThat(captor.getValue().getClassName()).isEqualTo(CLASS_NAME);
    }

    @Test
    void feeRuleForNonexistentClassIsRejected() {
        String unconfiguredClass = "10";
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, unconfiguredClass)).thenReturn(Optional.empty());

        FeeStructureRuleDto incoming = new FeeStructureRuleDto();
        incoming.setFeeHeadId(1L);
        incoming.setAmount(100_000L);
        incoming.setEffectiveFrom(LocalDate.of(2025, 4, 1));

        assertThatThrownBy(() ->
                service.saveRulesForClass(SESSION_ID, unconfiguredClass, List.of(incoming), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");

        verify(ruleRepository, never()).save(any());
        verify(feeHeadRepository, never()).findByIdAndSchoolId(anyLong(), anyLong());
    }

    @Test
    void feeRuleForClassBelongingToAnotherSchoolIsRejected() {
        // findBySchoolIdAndName is scoped by schoolId, so a class that only exists under a
        // different school can never satisfy the lookup for the caller's school.
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, CLASS_NAME)).thenReturn(Optional.empty());

        FeeStructureRuleDto incoming = new FeeStructureRuleDto();
        incoming.setFeeHeadId(1L);
        incoming.setAmount(100_000L);
        incoming.setEffectiveFrom(LocalDate.of(2025, 4, 1));

        assertThatThrownBy(() -> service.saveRulesForClass(SESSION_ID, CLASS_NAME, List.of(incoming), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");
        verify(ruleRepository, never()).save(any());
    }
}
