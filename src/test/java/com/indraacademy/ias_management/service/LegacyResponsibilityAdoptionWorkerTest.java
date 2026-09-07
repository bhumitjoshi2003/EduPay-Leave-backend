package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.AdoptionOutcome;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.ResponsibilityCandidate;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyResponsibilityAdoptionWorkerTest {

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CLASS_NO_SECTION_ID = 100L;
    private static final Long CLASS_WITH_SECTION_ID = 101L;
    private static final Long SECTION_A_ID = 200L;
    private static final Long SECTION_B_ID = 201L;

    @Mock private TeacherRepository teacherRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Mock private TimetableSessionAccessService sessionAccess;

    private LegacyResponsibilityAdoptionWorker worker;
    private AcademicSession session;
    private SchoolClass classNoSection;
    private SchoolClass classWithSection;
    private Section sectionA;
    private Section sectionB;

    @BeforeEach
    void setUp() {
        worker = new LegacyResponsibilityAdoptionWorker();
        ReflectionTestUtils.setField(worker, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(worker, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(worker, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(worker, "responsibilityRepository", responsibilityRepository);
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

        lenient().when(schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(SCHOOL_ID))
                .thenReturn(List.of(classNoSection, classWithSection));
        lenient().when(sectionRepository.findBySchoolIdOrderByClassIdAscDisplayOrderAsc(SCHOOL_ID))
                .thenReturn(List.of(sectionA, sectionB));
        lenient().when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID))
                .thenReturn(List.of());
    }

    private Teacher liveTeacher(String id, String className, Long sectionId) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setSchoolId(SCHOOL_ID);
        t.setStatus(TeacherStatus.ACTIVE);
        t.setClassTeacher(className);
        t.setClassTeacherSectionId(sectionId);
        return t;
    }

    private ResponsibilityCandidate find(List<ResponsibilityCandidate> details, String teacherId) {
        return details.stream().filter(d -> d.teacherId().equals(teacherId)).findFirst().orElseThrow();
    }

    @Test
    void classify_sectionlessClass_isSafe() {
        Teacher t = liveTeacher("T1", "Class 1", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        ResponsibilityCandidate c = find(result.details(), "T1");
        assertThat(c.outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(c.resolvedClassId()).isEqualTo(CLASS_NO_SECTION_ID);
        assertThat(c.resolvedSectionId()).isNull();
    }

    @Test
    void classify_sectionedClassWithResolvedSection_isSafe() {
        Teacher t = liveTeacher("T1", "Class 11", SECTION_A_ID);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        ResponsibilityCandidate c = find(result.details(), "T1");
        assertThat(c.outcome()).isEqualTo(AdoptionOutcome.SAFE);
        assertThat(c.resolvedSectionId()).isEqualTo(SECTION_A_ID);
    }

    @Test
    void classify_sectionedClassWithUnresolvedSectionId_requiresConfirmation() {
        Teacher t = liveTeacher("T1", "Class 11", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.REQUIRES_ADMIN_CONFIRMATION);
    }

    @Test
    void classify_sectionIdBelongsToDifferentClass_isInvalid() {
        Section otherClassSection = new Section();
        otherClassSection.setId(999L);
        otherClassSection.setClassId(CLASS_NO_SECTION_ID);
        otherClassSection.setName("X");
        otherClassSection.setActive(true);
        when(sectionRepository.findBySchoolIdOrderByClassIdAscDisplayOrderAsc(SCHOOL_ID))
                .thenReturn(List.of(sectionA, sectionB, otherClassSection));

        Teacher t = liveTeacher("T1", "Class 11", 999L);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_inactiveTeacher_isInvalid() {
        Teacher t = liveTeacher("T1", "Class 1", null);
        t.setStatus(TeacherStatus.LEFT);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_unknownClassName_isInvalid() {
        Teacher t = liveTeacher("T1", "Ghost Class", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_sectionlessClassWithUnexpectedSectionId_isInvalid() {
        Teacher t = liveTeacher("T1", "Class 1", SECTION_A_ID);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_twoTeachersSameClassSection_bothInvalid() {
        Teacher t1 = liveTeacher("T1", "Class 1", null);
        Teacher t2 = liveTeacher("T2", "Class 1", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t1, t2));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
        assertThat(find(result.details(), "T2").outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_existingExactMatch_isAlreadyAdopted() {
        Teacher t = liveTeacher("T1", "Class 1", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        ClassTeacherResponsibility existing = new ClassTeacherResponsibility();
        existing.setClassId(CLASS_NO_SECTION_ID);
        existing.setSectionId(null);
        existing.setTeacherId("T1");
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of(existing));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.ALREADY_ADOPTED);
    }

    @Test
    void classify_existingConflictingTeacher_isInvalid() {
        Teacher t = liveTeacher("T1", "Class 1", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        ClassTeacherResponsibility existing = new ClassTeacherResponsibility();
        existing.setClassId(CLASS_NO_SECTION_ID);
        existing.setSectionId(null);
        existing.setTeacherId("T-OTHER");
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of(existing));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        var result = worker.classify(SCHOOL_ID, SESSION_ID);

        assertThat(find(result.details(), "T1").outcome()).isEqualTo(AdoptionOutcome.INVALID_OR_CONFLICTING);
    }

    @Test
    void classify_dryRun_neverWritesAndNeverLocksOrTouchesTeacher() {
        Teacher t = liveTeacher("T1", "Class 1", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(t));
        when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        worker.classify(SCHOOL_ID, SESSION_ID);

        verify(responsibilityRepository, never()).save(any());
        verify(teacherRepository, never()).save(any());
        verify(sessionAccess, never()).lockWritableOwnedSession(anyLong(), anyLong());
    }

    @Test
    void classifyAndApply_locksSessionAndWritesOnlySafe_neverTouchesTeacher() {
        Teacher safe = liveTeacher("T1", "Class 1", null);
        Teacher invalid = liveTeacher("T2", "Ghost", null);
        when(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(SCHOOL_ID)).thenReturn(List.of(safe, invalid));
        when(sessionAccess.lockWritableOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);
        when(responsibilityRepository.save(any(ClassTeacherResponsibility.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = worker.classifyAndApply(SCHOOL_ID, SESSION_ID);

        verify(sessionAccess).lockWritableOwnedSession(SCHOOL_ID, SESSION_ID);
        verify(sessionAccess, never()).requireOwnedSession(anyLong(), anyLong());
        verify(responsibilityRepository, times(1)).save(any(ClassTeacherResponsibility.class));
        verify(teacherRepository, never()).save(any());
        assertThat(result.counters().adopted).isEqualTo(1);
    }
}
