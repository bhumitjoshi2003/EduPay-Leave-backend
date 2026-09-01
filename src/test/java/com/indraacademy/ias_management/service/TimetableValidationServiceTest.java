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
 */
@ExtendWith(MockitoExtension.class)
class TimetableValidationServiceTest {

    @Mock private TimetableRepository timetableRepository;

    private TimetableValidationService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new TimetableValidationService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        // No teacher-day entries anywhere unless a test overrides it.
        lenient().when(timetableRepository.findByTeacherIdAndDayAndSchoolId(any(), any(), any()))
                .thenReturn(List.of());
    }

    private TimetableEntry entry(Long id, Long sectionId, Day day, int period, String start, String end,
                                  String subject, String teacherId, String group) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
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

    private void stubSlot(List<TimetableEntry> existing) {
        when(timetableRepository.findByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                eq("10"), eq(Day.MONDAY), eq(3), eq(SCHOOL_ID))).thenReturn(existing);
    }

    @Test
    void emptySlot_anyCandidateAllowed() {
        stubSlot(List.of());
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, null)).doesNotThrowAnyException();
    }

    @Test
    void normalPlusNormalDuplicateSlot_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T1", null);
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "English", "T2", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void normalExistingPlusGroupedCandidate_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T1", null);
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T2", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void groupedExistingPlusNormalCandidate_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T2", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentSimultaneousGroupsInSameSlot_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Artificial Intelligence", "T2", "AI_MATHEMATICS");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("not part of the same simultaneous group");
    }

    @Test
    void sameGroupDifferentValidSubjects_allowed() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Biology", "T2", "MATH_BIO");

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, null)).doesNotThrowAnyException();
    }

    @Test
    void exactSameSubjectAndTeacher_rejectedAsDuplicate() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void sameSubjectDifferentCase_stillTreatedAsDuplicate() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "mathematics", "T1", "MATH_BIO");
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mismatchedTimesInSameGroup_rejected() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", "MATH_BIO");
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:45", "Biology", "T2", "MATH_BIO");

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("same start and end time");
    }

    @Test
    void blankGroupStringTreatedAsNull() {
        TimetableEntry existing = entry(1L, null, Day.MONDAY, 3, "09:00", "09:40", "Hindi", "T1", "   ");
        stubSlot(List.of(existing));
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "English", "T2", "");

        // Both sides normalize to "no group" → strict one-per-slot rule applies → reject.
        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void teacherDoubleBooking_differentClassSectionPeriod_rejected() {
        stubSlot(List.of()); // this candidate's own slot is free
        TimetableEntry conflicting = entry(9L, 2L, Day.MONDAY, 5, "09:10", "09:45", "Physics", "T1", null);
        conflicting.setClassName("11");
        when(timetableRepository.findByTeacherIdAndDayAndSchoolId("T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(conflicting));

        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("overlapping period");
    }

    @Test
    void teacherOverlap_differentPeriodNumbersButOverlappingTime_rejected() {
        stubSlot(List.of());
        TimetableEntry conflicting = entry(9L, null, Day.MONDAY, 4, "09:30", "10:10", "Chemistry", "T1", null);
        when(timetableRepository.findByTeacherIdAndDayAndSchoolId("T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(conflicting));

        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatThrownBy(() -> service.validate(candidate, SCHOOL_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameTeacherNonOverlappingTimes_allowed() {
        stubSlot(List.of());
        TimetableEntry other = entry(9L, null, Day.MONDAY, 4, "09:40", "10:20", "Chemistry", "T1", null);
        when(timetableRepository.findByTeacherIdAndDayAndSchoolId("T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(other));

        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, null)).doesNotThrowAnyException();
    }

    @Test
    void update_excludesItselfFromSlotAndTeacherConflictChecks() {
        TimetableEntry self = entry(5L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);
        stubSlot(List.of(self));
        when(timetableRepository.findByTeacherIdAndDayAndSchoolId("T1", Day.MONDAY, SCHOOL_ID))
                .thenReturn(List.of(self));

        // Re-validating the exact same entry (id=5) against itself must not conflict.
        TimetableEntry candidate = entry(5L, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        assertThatCode(() -> service.validate(candidate, SCHOOL_ID, 5L)).doesNotThrowAnyException();
    }

    @Test
    void tenantIsolation_queriesAreScopedBySchoolId() {
        stubSlot(List.of());
        TimetableEntry candidate = entry(null, null, Day.MONDAY, 3, "09:00", "09:40", "Mathematics", "T1", null);

        service.validate(candidate, SCHOOL_ID, null);

        org.mockito.Mockito.verify(timetableRepository)
                .findByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId("10", Day.MONDAY, 3, SCHOOL_ID);
        org.mockito.Mockito.verify(timetableRepository)
                .findByTeacherIdAndDayAndSchoolId("T1", Day.MONDAY, SCHOOL_ID);
    }
}
