package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.TimetableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Exhaustive unit tests for the slot-consistency and teacher-conflict rules shared by
 * TimetableService (manual create/update) and TimetableBulkImportService (CSV import).
 *
 * <p>Phase F3: every check is now scoped by an explicit {@code academicSessionId} and matched by
 * canonical {@code classId} rather than the className string.
 */
@ExtendWith(MockitoExtension.class)
class TimetableValidationServiceTest {

    @Mock private TimetableRepository timetableRepository;

    private TimetableValidationService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_1 = 10L;
    private static final Long SESSION_2 = 20L;
    private static final Long CLASS_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new TimetableValidationService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        // No teacher-day entries anywhere unless a test overrides it.
        lenient().when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private TimetableEntry entry(Long id, Long sectionId, Day day, int period, String start, String end,
                                  String subject, String teacherId, String group) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setClassId(CLASS_ID);
        e.setClassName("10");
        e.setSectionId(sectionId);
        e.setSectionName(sectionId != null ? "A" : null);
        e.setDay(day);
        e.setPeriodNumber(period);
        e.setStartTime(start);
        e.setEndTime(end);
        e.setSubjectName(subject);
        e.setTeacherId(teacherId);
        e.setSimultaneousGroup(group);
        return e;
    }

    private void stubSlot(Long sessionId, List<TimetableEntry> existing) {
        when(timetableRepository.findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                eq(sessionId), eq(CLASS_ID), eq(Day.MONDAY), eq(3), eq(SCHOOL_ID))).thenReturn(existing);
    }

    @Test
    void missingAcademicSessionId_rejectedUpFront() {
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingClassId_rejectedUpFront() {
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);
        candidate.setClassId(null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptySlot_anyCandidateAllowed() {
        stubSlot(SESSION_1, List.of());
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null)).doesNotThrowAnyException();
    }

    @Test
    void normalPlusNormalDuplicateSlot_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T1", null);
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "English", "T2", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void identicalSlotInADifferentSession_allowed() {
        // The exact same class/section/day/period/teacher/time as an existing SESSION_1 row —
        // but the candidate targets SESSION_2, where the repository correctly reports no occupant.
        stubSlot(SESSION_2, List.of());
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, SESSION_2, null)).doesNotThrowAnyException();

        org.mockito.Mockito.verify(timetableRepository)
                .findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                        SESSION_2, CLASS_ID, Day.MONDAY, 3, SCHOOL_ID);
        org.mockito.Mockito.verify(timetableRepository, org.mockito.Mockito.never())
                .findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                        eq(SESSION_1), any(), any(), any(), any());
    }

    @Test
    void normalExistingPlusGroupedCandidate_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T1", null);
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T2", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void groupedExistingPlusNormalCandidate_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T2", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentSimultaneousGroupsInSameSlot_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Artificial Intelligence", "T2", "AI_MATHEMATICS");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("not part of the same simultaneous group");
    }

    @Test
    void sameGroupDifferentValidSubjects_allowed() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Biology", "T2", "MATH_BIO");

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null)).doesNotThrowAnyException();
    }

    @Test
    void exactSameSubjectAndTeacher_rejectedAsDuplicate() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void sameSubjectDifferentCase_stillTreatedAsDuplicate() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "mathematics", "T1", "MATH_BIO");
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mismatchedTimesInSameGroup_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:45", "Biology", "T2", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("same start and end time");
    }

    @Test
    void blankGroupStringTreatedAsNull() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T1", "   ");
        stubSlot(SESSION_1, List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "English", "T2", "");

        // Both sides normalize to "no group" → strict one-per-slot rule applies → reject.
        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void teacherDoubleBooking_differentClassSectionPeriod_rejected() {
        stubSlot(SESSION_1, List.of()); // this candidate's own slot is free
        TimetableEntry conflicting = entry(9L, 2L, Day.MONDAY, 5, "09:10", "09:45", "Physics", "T1", null);
        conflicting.setClassId(999L);
        conflicting.setClassName("11");
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(SESSION_1, "T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(conflicting));

        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("overlapping period");
    }

    @Test
    void teacherOverlap_differentPeriodNumbersButOverlappingTime_rejected() {
        stubSlot(SESSION_1, List.of());
        TimetableEntry conflicting = entry(9L, null, Day.MONDAY, 4, "09:30", "10:10", "Chemistry", "T1", null);
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(SESSION_1, "T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(conflicting));

        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void teacherOverlap_sameTeacherSameTimeButDifferentSession_allowed() {
        // The exact same teacher/day/overlapping-time conflict as the rejected test above, but
        // the candidate targets SESSION_2, where the repository correctly reports nothing —
        // recurring the same weekly slot in a different academic year is not a conflict.
        stubSlot(SESSION_2, List.of());
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(SESSION_2, "T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of());

        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, SESSION_2, null)).doesNotThrowAnyException();
        org.mockito.Mockito.verify(timetableRepository, org.mockito.Mockito.never())
                .findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(eq(SESSION_1), any(), any(), any());
    }

    @Test
    void sameTeacherNonOverlappingTimes_allowed() {
        stubSlot(SESSION_1, List.of());
        TimetableEntry other = entry(9L, null, Day.MONDAY, 4, "09:40", "10:20", "Chemistry", "T1", null);
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(SESSION_1, "T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(other));

        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, null)).doesNotThrowAnyException();
    }

    @Test
    void update_excludesItselfFromSlotAndTeacherConflictChecks() {
        TimetableEntry self = entry(5L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);
        stubSlot(SESSION_1, List.of(self));
        when(timetableRepository.findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(SESSION_1, "T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(self));

        // Re-validating the exact same entry (id=5) against itself must not conflict.
        TimetableEntry candidate = entry(5L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, SESSION_1, 5L)).doesNotThrowAnyException();
    }

    @Test
    void tenantAndSessionIsolation_queriesAreScopedBySchoolIdAndSession() {
        stubSlot(SESSION_1, List.of());
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        service.validate(candidate, SCHOOL_ID, SESSION_1, null);

        org.mockito.Mockito.verify(timetableRepository)
                .findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                        SESSION_1, CLASS_ID, Day.MONDAY, 3, SCHOOL_ID);
        org.mockito.Mockito.verify(timetableRepository)
                .findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(SESSION_1, "T1", Day.MONDAY, SCHOOL_ID);
    }
}
