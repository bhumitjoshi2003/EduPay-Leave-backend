package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of the format/rollover arithmetic and role/counter-key wiring.
 * Real concurrency/uniqueness under actual Postgres is covered separately by
 * IdSequenceCounterConcurrencyTest, which hits the real database — nothing here mocks
 * concurrency, since a mock can't prove anything about real row-locking behavior.
 */
@ExtendWith(MockitoExtension.class)
class IdGeneratorServiceTest {

    @Mock private IdSequenceCounterService counterService;

    private IdGeneratorService service;

    void setUp() {
        service = new IdGeneratorService();
        ReflectionTestUtils.setField(service, "counterService", counterService);
    }

    // ─── Year-code rollover arithmetic (pure — no DB, no mocking of "now") ───

    @Test
    void yearCode2026Is26() {
        assertThat(IdGeneratorService.yearCodeFor(2026)).isEqualTo(26);
    }

    @Test
    void yearCode2099Is99_theLastTwoDigitYear() {
        assertThat(IdGeneratorService.yearCodeFor(2099)).isEqualTo(99);
    }

    @Test
    void yearCode2100Is100_rolloverToThreeDigits_neverCollidesWithAnyTwoDigitYear() {
        int code2100 = IdGeneratorService.yearCodeFor(2100);
        assertThat(code2100).isEqualTo(100);
        // The exact collision this design avoids: naively using "last two digits" would make
        // 2100 indistinguishable from 2000. Confirm the actual scheme never produces that.
        assertThat(code2100).isNotEqualTo(IdGeneratorService.yearCodeFor(2000));
    }

    @Test
    void yearCode2126Is126() {
        assertThat(IdGeneratorService.yearCodeFor(2126)).isEqualTo(126);
    }

    // ─── ID string formatting ─────────────────────────────────────────────

    @Test
    void formatsSequenceZeroPaddedToSixDigits() {
        assertThat(IdGeneratorService.format("stu", 26, 10001)).isEqualTo("stu_26010001");
        assertThat(IdGeneratorService.format("stu", 26, 1)).isEqualTo("stu_26000001");
    }

    @Test
    void formatsThreeDigitYearWithoutPadding() {
        assertThat(IdGeneratorService.format("stu", 100, 10001)).isEqualTo("stu_100010001");
        assertThat(IdGeneratorService.format("stu", 126, 10001)).isEqualTo("stu_126010001");
    }

    @Test
    void sequenceWiderThanSixDigitsIsNotTruncated() {
        // %0Nd is a MINIMUM width in Java's Formatter — never a maximum/truncation. A far-off
        // hypothetical year in which one role's counter exceeds 999999 in a single year still
        // produces a distinguishable, non-colliding string, just a longer one.
        assertThat(IdGeneratorService.format("stu", 26, 1000000)).isEqualTo("stu_261000000");
    }

    // ─── Role → prefix / counter-key wiring ────────────────────────────────

    @Test
    void generateStudentId_usesStuPrefixAndStuCounterKey() {
        setUp();
        when(counterService.nextSequence(eq("STU"), anyInt())).thenReturn(10001L);
        assertThat(service.generateStudentId()).isEqualTo("stu_" + currentYearCode() + "010001");
    }

    @Test
    void generateTeacherId_usesEmpPrefixAndEmpCounterKey() {
        setUp();
        when(counterService.nextSequence(eq("EMP"), anyInt())).thenReturn(10001L);
        assertThat(service.generateTeacherId()).isEqualTo("emp_" + currentYearCode() + "010001");
    }

    @Test
    void generateParentId_usesParPrefixAndParCounterKey() {
        setUp();
        when(counterService.nextSequence(eq("PAR"), anyInt())).thenReturn(10001L);
        assertThat(service.generateParentId()).isEqualTo("par_" + currentYearCode() + "010001");
    }

    @Test
    void studentTeacherParentCountersAreIndependent_eachAsksForItsOwnKey() {
        setUp();
        when(counterService.nextSequence(eq("STU"), anyInt())).thenReturn(1L);
        when(counterService.nextSequence(eq("EMP"), anyInt())).thenReturn(1L);
        when(counterService.nextSequence(eq("PAR"), anyInt())).thenReturn(1L);

        // All three start at their own "1" independently in this stub — proves the service
        // asks the counter store for three genuinely distinct keys, not one shared key.
        assertThat(service.generateStudentId()).startsWith("stu_");
        assertThat(service.generateTeacherId()).startsWith("emp_");
        assertThat(service.generateParentId()).startsWith("par_");
    }

    private int currentYearCode() {
        return IdGeneratorService.yearCodeFor(LocalDate.now().getYear());
    }
}
