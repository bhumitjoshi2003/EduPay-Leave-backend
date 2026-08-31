package com.indraacademy.ias_management.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Generates the system-assigned Edunexify IDs for newly created Student, Employee
 * (Teacher), and Parent accounts — {@code stu_<yearCode><seq>}, {@code emp_<yearCode><seq>},
 * {@code par_<yearCode><seq>}.
 *
 * <p>{@code yearCode} is the current calendar year minus 2000 (2026 → 26, 2100 → 100,
 * 2126 → 126 — deliberately unpadded, so the 100-year rollover never collides with a
 * two-digit year the way a fixed-width last-two-digits scheme would). {@code seq} is a
 * 6-digit-minimum, zero-padded, strictly increasing counter starting at 010001 for every
 * (role, year) combination, reset to 010001 again at the start of each new year. Counters
 * for Student/Employee/Parent are entirely independent of each other and are global across
 * the whole platform, not scoped per school — matching the approved Phase 1 design.
 *
 * <p>Existing accounts are completely unaffected: this service is only ever called from a
 * "create a brand-new account" code path (manual registration, bulk import, or Parent
 * creation/bulk import), never from anywhere that touches an existing row.
 *
 * <p>This is invoked in its own short {@code REQUIRES_NEW} transaction, separate from
 * whatever larger transaction the caller is in — the counter row's lock is held only for
 * the single atomic upsert, not for the rest of account creation. The accepted consequence
 * is that a later failure elsewhere in account creation (e.g. a validation error) leaves a
 * permanent small gap in the sequence — the number generated here is simply never reused.
 * This is the same trade-off any database auto-increment sequence makes on rollback, and is
 * explicitly acceptable per the approved design: uniqueness and concurrency safety matter,
 * contiguous numbering does not.
 */
@Service
public class IdGeneratorService {

    private static final int SEQUENCE_WIDTH = 6;

    @Autowired private IdSequenceCounterService counterService;

    public String generateStudentId() {
        return generate("stu", "STU");
    }

    public String generateTeacherId() {
        return generate("emp", "EMP");
    }

    public String generateParentId() {
        return generate("par", "PAR");
    }

    private String generate(String idPrefix, String counterKey) {
        int yearCode = yearCodeFor(LocalDate.now().getYear());
        long seq = counterService.nextSequence(counterKey, yearCode);
        return format(idPrefix, yearCode, seq);
    }

    /** Pure, independently-testable rollover arithmetic: 2026 → 26, 2099 → 99, 2100 → 100,
     *  2126 → 126 — deliberately unpadded so the 100-year rollover can never collide with a
     *  fixed-two-digit scheme. Package-private so IdGeneratorServiceTest can exercise years
     *  other than "whichever year it happens to be when the test runs." */
    static int yearCodeFor(int calendarYear) {
        return calendarYear - 2000;
    }

    /** Pure formatting, split out for the same testability reason as yearCodeFor. */
    static String format(String idPrefix, int yearCode, long seq) {
        return idPrefix + "_" + yearCode + String.format("%0" + SEQUENCE_WIDTH + "d", seq);
    }
}
