package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.AdoptionOutcome;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.TimetableCandidate;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyTimetableAdoptionWorkerTest {

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CLASS_NO_SECTION_ID = 100L;
    private static final Long CLASS_WITH_SECTION_ID = 101L;
    private static final Long SECTION_A_ID = 200L;
    private static final Long SECTION_B_ID = 201L;

    @Mock private TimetableRepository timetableRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private TimetableSessionAccessService sessionAccess;

    private LegacyTimetableAdoptionWorker worker;
    private AcademicSession session;
    private SchoolClass classNoSection;
    private SchoolClass classWithSection;
    private Section sectionA;
    private Section sectionB;
    private Teacher activeTeacher;

    @BeforeEach
    void setUp() {
        worker = new LegacyTimetableAdoptionWorker();
        ReflectionTestUtils.setField(worker, "timetableRepository", timetableRepository);
        ReflectionTestUtils.setField(worker, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(worker, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(worker, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(worker, "sessionAccess", sessionAccess);

        session = new AcademicSession();
        session.setId(SESSION_ID);
        session.setSchoolId(SCHOOL_ID);

        classNoSection = new SchoolClass();
        classNoSection.setId(CLASS_NO_SECTION_ID);
        classNoSection.setSchoolId(SCHOOL_ID);
        classNoSection.setName("Class 1");

        classWithSection = new SchoolClass();
        classWithSection.setId(CLASS_WITH_SECTION_ID);
        classWithSection.setSchoolId(SCHOOL_ID);
        classWithSection.setName("Class 11");

        sectionA = new Section();
        sectionA.setId(SECTION_A_ID);
        sectionA.setClassId(CLASS_WITH_SECTION_ID);
        sectionA.setName("A");
        sectionA.setActive(true);

        sectionB = new Section();
        sectionB.setId(SECTION_B_ID);
        sectionB.setClassId(CLASS_WITH_SECTION_ID);
        sectionB.setName("B");
        sectionB.setActive(true);

        activeTeacher = new Teacher();
        activeTeacher.setTeacherId("T1");
        activeTeacher.setSchoolId(SCHOOL_ID);
        activeTeacher.setStatus(TeacherStatus.ACTIVE);

        lenient().when(schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(SCHOOL_ID))
                .thenReturn(List.of(classNoSection, classWithSection));
        lenient().when(sectionRepository.findBySchoolIdOrderByClassIdAscDisplayOrderAsc(SCHOOL_ID))
                .thenReturn(List.of(sectionA, sectionB));
        lenient().when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(activeTeacher));
        lenient().when(timetableRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of());
    }

    private TimetableEntry legacyRow(long id, String className, String sectionName, Day day, int period,
            String start, String end, String subject, String teacherId, String group) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setSchoolId(SCHOOL_ID);
        e.setAcademicSessionId(null);
        e.setClassId(null);
        e.setClassName(className);
        e.setSectionId(null);
        e.setSectionName(sectionName);
        e.setDay(day);
        e.setPeriodNumber(period);
        e.setStartTime(start);
        e.setEndTime(end);
        e.setSubjectName(subject);
        e.setTeacherId(teacherId);
        e.setSimultaneousGroup(group);
        return e;
    }

    private TimetableCandidate find(List<TimetableCandidate> details, long id) {
        return details.stream().filter(d -> d.timetableEntryId() == id).findFirst().orElseThrow();
    }

    @Test
    void classify_sectionlessClass_resolvesSafeWithNullSectionId() {
        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        TimetableCandidate c = find(result.details(), 1);
        assertThat(c.outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(c.resolvedClassId()).isEqualTo(CLASS_NO_SECTION_ID);
        assertThat(c.resolvedSectionId()).isNull();
        assertThat(result.counters().safe).isEqualTo(1);
    }

    @Test
    void classify_sectionedClass_resolvesSafeWithExactSectionMatch() {
        TimetableEntry row = legacyRow(1, "Class 11", "A", Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        TimetableCandidate c = find(result.details(), 1);
        assertThat(c.outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(c.resolvedClassId()).isEqualTo(CLASS_WITH_SECTION_ID);
        assertThat(c.resolvedSectionId()).isEqualTo(SECTION_A_ID);
    }

    @Test
    void classify_unknownClassName_isInvalid() {
        TimetableEntry row = legacyRow(1, "Ghost Class", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        TimetableCandidate c = find(result.details(), 1);
        assertThat(c.outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
        assertThat(result.counters().unresolvedClassMappings).isEqualTo(1);
    }

    @Test
    void classify_sectionedClassMissingSectionName_isInvalid() {
        TimetableEntry row = legacyRow(1, "Class 11", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_unknownSectionName_isInvalid() {
        TimetableEntry row = legacyRow(1, "Class 11", "Z", Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_sectionlessClassWithUnexpectedSectionName_requiresConfirmation() {
        TimetableEntry row = legacyRow(1, "Class 1", "A", Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.REQUIRES_ADMIN_CONFIRMATION);
    }

    @Test
    void classify_unknownTeacher_isInvalid() {
        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", "GHOST", null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        TimetableCandidate c = find(result.details(), 1);
        assertThat(c.outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
        assertThat(result.counters().invalidOrIneligibleTeachers).isEqualTo(1);
    }

    @Test
    void classify_inactiveTeacher_requiresConfirmation() {
        Teacher left = new Teacher();
        left.setTeacherId("T-LEFT");
        left.setSchoolId(SCHOOL_ID);
        left.setStatus(TeacherStatus.LEFT);
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(activeTeacher, left));

        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", "T-LEFT", null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.REQUIRES_ADMIN_CONFIRMATION);
    }

    @Test
    void classify_malformedTime_isInvalid() {
        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "10:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_twoRowsSameUngroupedSlot_secondIsSlotConflict() {
        TimetableEntry row1 = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        TimetableEntry row2 = legacyRow(2, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Science", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row1, row2));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(find(result.details(), 2).outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
        assertThat(result.counters().slotConflicts).isEqualTo(1);
    }

    @Test
    void classify_collidesWithExistingTargetSessionRow_isSlotConflict() {
        TimetableEntry existing = legacyRow(99, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Existing", null, null);
        existing.setAcademicSessionId(SESSION_ID);
        existing.setClassId(CLASS_NO_SECTION_ID);
        when(timetableRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of(existing));

        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_teacherOverlap_secondRowIsInvalid() {
        TimetableEntry row1 = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", "T1", null);
        TimetableEntry row2 = legacyRow(2, "Class 11", "A", Day.MONDAY, 1, "09:20", "10:00", "Science", "T1", null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row1, row2));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(find(result.details(), 2).outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
        assertThat(result.counters().teacherOverlaps).isEqualTo(1);
    }

    @Test
    void classify_loneSimultaneousTag_isSafeButFlaggedAsConcern() {
        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, "MATH_BIO");
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        TimetableCandidate c = find(result.details(), 1);
        assertThat(c.outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(c.simultaneousGroupConcern()).isTrue();
        assertThat(result.counters().simultaneousGroupConcerns).isEqualTo(1);
    }

    @Test
    void classify_matchingSimultaneousPair_bothSafeNoConcern() {
        TimetableEntry row1 = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", "T1", "MATH_BIO");
        TimetableEntry row2 = legacyRow(2, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Biology", null, "MATH_BIO");
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row1, row2));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(find(result.details(), 2).outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(find(result.details(), 1).simultaneousGroupConcern()).isFalse();
        assertThat(find(result.details(), 2).simultaneousGroupConcern()).isFalse();
        assertThat(result.counters().simultaneousGroupConcerns).isZero();
    }

    @Test
    void classify_simultaneousPairWithMismatchedTimes_secondIsInvalid() {
        TimetableEntry row1 = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, "MATH_BIO");
        TimetableEntry row2 = legacyRow(2, "Class 1", null, Day.MONDAY, 1, "09:00", "09:45", "Biology", null, "MATH_BIO");
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row1, row2));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 2).outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_alreadyInTargetSessionWithClassId_isAlreadyAdopted() {
        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        row.setAcademicSessionId(SESSION_ID);
        row.setClassId(CLASS_NO_SECTION_ID);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.ALREADY_ADOPTED);
        assertThat(result.counters().alreadyAdopted).isEqualTo(1);
    }

    @Test
    void classify_belongsToDifferentSession_isSkipped() {
        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        row.setAcademicSessionId(999L);
        row.setClassId(CLASS_NO_SECTION_ID);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), 1).outcome()).isEqualTo(AdoptionOutcome.SKIPPED);
        assertThat(result.counters().skipped).isEqualTo(1);
    }

    @Test
    void classify_dryRun_neverWritesAndNeverLocks() {
        TimetableEntry row = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        worker.classify(SCHOOL_ID, SESSION_ID);

        verify(timetableRepository, never()).save(any());
        verify(sessionAccess, never()).lockWritableOwnedSession(anyLong(), anyLong());
    }

    @Test
    void classifyAndApply_locksSessionAndWritesOnlySafeRows() {
        TimetableEntry safeRow = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        TimetableEntry invalidRow = legacyRow(2, "Ghost", null, Day.MONDAY, 2, "09:00", "09:40", "Math", null, null);
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(safeRow, invalidRow));
        when(sessionAccess.lockWritableOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);
        when(timetableRepository.save(any(TimetableEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = worker.classifyAndApply(SCHOOL_ID, SESSION_ID);

        verify(sessionAccess).lockWritableOwnedSession(SCHOOL_ID, SESSION_ID);
        verify(sessionAccess, never()).requireOwnedSession(anyLong(), anyLong());
        verify(timetableRepository, times(1)).save(any(TimetableEntry.class));
        assertThat(result.counters().adopted).isEqualTo(1);
        assertThat(safeRow.getAcademicSessionId()).isEqualTo(SESSION_ID);
        assertThat(safeRow.getClassId()).isEqualTo(CLASS_NO_SECTION_ID);
    }

    /** {@code classifyAndApply} carries no explicit rollback logic of its own — atomicity comes
     *  entirely from Spring's {@code @Transactional} wrapping the whole method (same mechanism,
     *  same proof style, as F4.1's {@code ClassTeacherActivationServiceTest
     *  #apply_failureBeforeWritesComplete_neverRecordsProvenance}): if ANY row's write throws,
     *  the exception must propagate all the way out uncaught, which is what triggers Spring's
     *  rollback in production — this test proves the propagation half of that guarantee; the
     *  actual rollback is proven against a real transaction in {@code LegacyAdoptionPostgresIT}. */
    @Test
    void classifyAndApply_saveFailurePartwayThrough_propagatesAndStopsAdopting() {
        TimetableEntry row1 = legacyRow(1, "Class 1", null, Day.MONDAY, 1, "09:00", "09:40", "Math", null, null);
        TimetableEntry row2 = legacyRow(2, "Class 2", null, Day.MONDAY, 1, "09:00", "09:40", "Science", null, null);
        SchoolClass class2 = new SchoolClass();
        class2.setId(102L);
        class2.setSchoolId(SCHOOL_ID);
        class2.setName("Class 2");
        when(schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(SCHOOL_ID))
                .thenReturn(List.of(classNoSection, classWithSection, class2));
        when(timetableRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(row1, row2));
        when(sessionAccess.lockWritableOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);
        when(timetableRepository.save(row1)).thenAnswer(inv -> inv.getArgument(0));
        when(timetableRepository.save(row2)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> worker.classifyAndApply(SCHOOL_ID, SESSION_ID));
    }
}
