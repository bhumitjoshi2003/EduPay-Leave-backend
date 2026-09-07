package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentEnrollmentServiceTest {
    static final long SCHOOL=1, SESSION=10, CLASS_9=20, CLASS_10=21, SECTION_A=30, SECTION_B=31;
    static final LocalDate TODAY=LocalDate.of(2026,9,6);
    @Mock SchoolRepository schools;
    @Mock StudentRepository students;
    @Mock AcademicSessionRepository sessions;
    @Mock SchoolClassRepository classes;
    @Mock SectionRepository sections;
    @Mock StudentEnrollmentRepository enrollments;
    StudentEnrollmentService service;
    Student student;
    AcademicSession session;

    @BeforeEach void setup(){
        service=new StudentEnrollmentService(schools,students,sessions,classes,sections,enrollments,
                Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(),ZoneId.systemDefault()));
        student=student(); session=session();
        lenient().when(schools.existsById(SCHOOL)).thenReturn(true);
        School school=new School(); school.setId(SCHOOL); school.setTimezone("Asia/Kolkata");
        lenient().when(schools.findById(SCHOOL)).thenReturn(Optional.of(school));
        lenient().when(students.findByStudentIdAndSchoolIdForUpdate("S1",SCHOOL)).thenReturn(Optional.of(student));
        lenient().when(students.findByStudentIdAndSchoolId("S1",SCHOOL)).thenReturn(Optional.of(student));
        lenient().when(sessions.findByIdAndSchoolId(SESSION,SCHOOL)).thenReturn(Optional.of(session));
        lenient().when(classes.findByIdAndSchoolId(CLASS_9,SCHOOL)).thenReturn(Optional.of(schoolClass(CLASS_9,"9")));
        lenient().when(classes.findByIdAndSchoolId(CLASS_10,SCHOOL)).thenReturn(Optional.of(schoolClass(CLASS_10,"10")));
        lenient().when(sections.findByIdAndSchoolId(SECTION_A,SCHOOL)).thenReturn(Optional.of(section(SECTION_A,CLASS_9,"A")));
        lenient().when(sections.findByIdAndSchoolId(SECTION_B,SCHOOL)).thenReturn(Optional.of(section(SECTION_B,CLASS_10,"B")));
        lenient().when(enrollments.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        lenient().when(students.save(any())).thenAnswer(i->i.getArgument(0));
    }

    @Test void effectiveAndOpenLookupsAreTenantScoped(){
        StudentEnrollment e=active();
        when(enrollments.findEffectiveEnrollment(SCHOOL,"S1",SESSION,TODAY)).thenReturn(Optional.of(e));
        when(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdAndStatusInAndEffectiveUntilIsNull(
                eq(SCHOOL),eq("S1"),eq(SESSION),any())).thenReturn(Optional.of(e));
        assertThat(service.findEffectiveEnrollment(SCHOOL,"S1",SESSION,TODAY)).contains(e);
        assertThat(service.findOpenEnrollment(SCHOOL,"S1",SESSION)).contains(e);
    }

    @Test void currentClassTransitionClosesAtPreviousDayAndUpdatesProjection(){
        StudentEnrollment old=active(); when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(old));
        var result=service.transitionClass(SCHOOL,"S1",SESSION,CLASS_10,SECTION_B,TODAY);
        assertThat(result.closedSegment().getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
        assertThat(result.closedSegment().getEffectiveUntil()).isEqualTo(TODAY.minusDays(1));
        assertThat(result.closedSegment().getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.CLASS_CHANGE);
        assertThat(result.replacementSegment().getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(result.replacementSegment().getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(student.getClassId()).isEqualTo(CLASS_10);
        assertThat(student.getClassName()).isEqualTo("10");
        assertThat(student.getSectionName()).isEqualTo("B");
    }

    @Test void sectionTransitionKeepsClassAndUsesValidatedSnapshot(){
        StudentEnrollment old=active();
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(old));
        when(sections.findByIdAndSchoolId(32L,SCHOOL)).thenReturn(Optional.of(section(32,CLASS_9,"C")));
        var result=service.transitionSection(SCHOOL,"S1",SESSION,32L,TODAY);
        assertThat(result.closedSegment().getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.SECTION_CHANGE);
        assertThat(result.replacementSegment().getClassId()).isEqualTo(CLASS_9);
        assertThat(result.replacementSegment().getSectionNameSnapshot()).isEqualTo("C");
        assertThat(student.getSectionName()).isEqualTo("C");
    }

    @Test void futureTransitionCreatesPlannedAndDoesNotChangeProjection(){
        StudentEnrollment old=active(); when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(old));
        var result=service.transitionClass(SCHOOL,"S1",SESSION,CLASS_10,SECTION_B,TODAY.plusDays(10));
        assertThat(result.closedSegment().getEffectiveUntil()).isEqualTo(TODAY.plusDays(9));
        assertThat(result.replacementSegment().getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        assertThat(student.getClassId()).isEqualTo(CLASS_9);
        verify(students,never()).save(any());
    }

    @Test void plannedCreationDoesNotChangeProjection(){
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of());
        StudentEnrollment planned=service.createPlannedEnrollment(SCHOOL,"S1",SESSION,CLASS_10,SECTION_B,TODAY.plusDays(1));
        assertThat(planned.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        assertThat(planned.getClassNameSnapshot()).isEqualTo("10");
        assertThat(student.getClassId()).isEqualTo(CLASS_9);
        verify(students,never()).save(any());
    }

    @Test void activationOnDateAndLateActivationUpdateProjection(){
        for(LocalDate activation:List.of(TODAY,TODAY.plusDays(2))){
            StudentEnrollment planned=planned(TODAY); planned.setId(200L);
            when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(planned));
            StudentEnrollment result=service.activatePlannedEnrollment(SCHOOL,"S1",SESSION,200L,activation);
            assertThat(result.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
            assertThat(student.getClassId()).isEqualTo(CLASS_10);
        }
    }

    @Test void earlyActivationIsRejected(){
        StudentEnrollment planned=planned(TODAY.plusDays(1)); planned.setId(200L);
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(planned));
        assertThatThrownBy(()->service.activatePlannedEnrollment(SCHOOL,"S1",SESSION,200L,TODAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("before");
    }

    @Test void duplicateActivationIsIdempotent(){
        StudentEnrollment active=planned(TODAY); active.setId(200L); active.setStatus(StudentEnrollmentStatus.ACTIVE);
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(active));
        assertThat(service.activatePlannedEnrollment(SCHOOL,"S1",SESSION,200L,TODAY)).isSameAs(active);
        verify(enrollments,never()).saveAndFlush(any());
    }

    @Test void invalidTenantClassAndCrossClassSectionAreRejected(){
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(active()));
        when(classes.findByIdAndSchoolId(99L,SCHOOL)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.transitionClass(SCHOOL,"S1",SESSION,99L,null,TODAY))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(()->service.transitionClass(SCHOOL,"S1",SESSION,CLASS_10,SECTION_A,TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Section");
    }

    @Test void datesOutsideSessionAreRejectedAndSameDayTransitionCorrectsSegmentInPlace(){
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(active()));
        assertThatThrownBy(()->service.transitionClass(SCHOOL,"S1",SESSION,CLASS_10,null,session.getEndDate().plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside");
        StudentEnrollment sameDay=active();
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(sameDay));
        var result=service.transitionClass(SCHOOL,"S1",SESSION,CLASS_10,null,sameDay.getEffectiveFrom());
        assertThat(result.closedSegment()).isNull();
        assertThat(result.replacementSegment()).isSameAs(sameDay);
        assertThat(sameDay.getClassId()).isEqualTo(CLASS_10);
        assertThat(student.getClassId()).isEqualTo(CLASS_10);
    }

    @Test void activeCreationUsesValidatedMembershipAndSynchronizesProjection(){
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of());
        StudentEnrollment active=service.createActiveEnrollment(
                SCHOOL,"S1",SESSION,CLASS_10,SECTION_B,TODAY);
        assertThat(active.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(active.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(student.getClassId()).isEqualTo(CLASS_10);
        assertThat(student.getSectionId()).isEqualTo(SECTION_B);
    }

    @Test void closeUsesInclusiveFinalDateAndExplicitlyClearsExpiredProjection(){
        StudentEnrollment old=active(); old.setId(100L);
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(old));
        StudentEnrollment closed=service.closeEnrollment(SCHOOL,"S1",SESSION,100L,TODAY.minusDays(1),
                StudentEnrollmentClosureReason.WITHDRAWN,
                StudentEnrollmentService.ProjectionOnClose.CLEAR_IF_NO_LONGER_EFFECTIVE);
        assertThat(closed.getEffectiveUntil()).isEqualTo(TODAY.minusDays(1));
        assertThat(closed.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.WITHDRAWN);
        assertThat(student.getClassId()).isNull();
    }

    @Test void closeRequiresCallerProjectionDecision(){
        assertThatThrownBy(()->service.closeEnrollment(SCHOOL,"S1",SESSION,100L,TODAY,
                StudentEnrollmentClosureReason.WITHDRAWN,null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void consistencyDetectionAndExplicitRepairUseEnrollmentAsAuthority(){
        StudentEnrollment authoritative=active(); authoritative.setId(100L);
        student.setClassId(CLASS_10); student.setClassName("wrong");
        when(enrollments.findEffectiveEnrollment(SCHOOL,"S1",SESSION,TODAY)).thenReturn(Optional.of(authoritative));
        assertThat(service.checkProjectionConsistency(SCHOOL,"S1",SESSION).consistent()).isFalse();
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(authoritative));
        assertThat(service.repairProjectionFromEnrollment(SCHOOL,"S1",SESSION).consistent()).isTrue();
        assertThat(student.getClassName()).isEqualTo("9");
        assertThat(authoritative.getClassNameSnapshot()).isEqualTo("9");
    }

    @Test void lockOrderIsStudentThenEnrollment(){
        when(enrollments.findHistoryForUpdate(SCHOOL,"S1",SESSION)).thenReturn(List.of(active()));
        service.transitionClass(SCHOOL,"S1",SESSION,CLASS_10,null,TODAY);
        InOrder order=inOrder(students,enrollments);
        order.verify(students).findByStudentIdAndSchoolIdForUpdate("S1",SCHOOL);
        order.verify(enrollments).findHistoryForUpdate(SCHOOL,"S1",SESSION);
    }

    @Test void serviceHasNoFinancialDependencies(){
        assertThat(List.of(StudentEnrollmentService.class.getDeclaredFields()))
                .extracting(java.lang.reflect.Field::getType)
                .noneMatch(t->t.getSimpleName().contains("Fee")||t.getSimpleName().contains("Payment"));
    }

    @Test void explicitExitClosesInclusiveAndPreservesProjection(){
        StudentEnrollment current=active();
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(current));
        var result=service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,
                StudentEnrollmentClosureReason.WITHDRAWN);
        assertThat(result.legacyUncovered()).isFalse();
        assertThat(result.enrollment().getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
        assertThat(result.enrollment().getEffectiveUntil()).isEqualTo(TODAY);
        assertThat(result.enrollment().getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.WITHDRAWN);
        assertThat(student.getClassId()).isEqualTo(CLASS_9);
    }

    @Test void exitOnFirstDayProducesValidOneDayClosedSegment(){
        StudentEnrollment current=active(); current.setEffectiveFrom(TODAY);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(current));
        var closed=service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,
                StudentEnrollmentClosureReason.TRANSFERRED).enrollment();
        assertThat(closed.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(closed.getEffectiveUntil()).isEqualTo(TODAY);
    }

    @Test void invalidOrRepeatedExitIsRejectedWithoutRewritingHistory(){
        StudentEnrollment current=active(); current.setEffectiveFrom(TODAY.plusDays(1));
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(current));
        assertThatThrownBy(()->service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,
                StudentEnrollmentClosureReason.GRADUATED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No effective");
        student.setStatus(StudentStatus.WITHDRAWN);
        assertThatThrownBy(()->service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,
                StudentEnrollmentClosureReason.WITHDRAWN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Only ACTIVE");
        verify(enrollments,never()).saveAndFlush(any());
    }

    @Test void readmissionCreatesNewSegmentAndLeavesClosedHistoryImmutable(){
        student.setStatus(StudentStatus.WITHDRAWN);
        StudentEnrollment old=active(); old.setStatus(StudentEnrollmentStatus.CLOSED);
        old.setEffectiveUntil(TODAY.minusDays(2)); old.setClosureReason(StudentEnrollmentClosureReason.WITHDRAWN);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(old));
        var result=service.createForExplicitReadmission(
                SCHOOL,"S1",SESSION,CLASS_9,SECTION_A,TODAY);
        assertThat(result.enrollment()).isNotSameAs(old);
        assertThat(result.enrollment().getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(result.enrollment().getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(old.getEffectiveUntil()).isEqualTo(TODAY.minusDays(2));
        assertThat(student.getClassName()).isEqualTo("9");
    }

    @Test void overlappingAndCrossClassReadmissionAreRejected(){
        student.setStatus(StudentStatus.WITHDRAWN);
        StudentEnrollment old=active(); old.setStatus(StudentEnrollmentStatus.CLOSED);
        old.setEffectiveUntil(TODAY); old.setClosureReason(StudentEnrollmentClosureReason.WITHDRAWN);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(old));
        assertThatThrownBy(()->service.createForExplicitReadmission(
                SCHOOL,"S1",SESSION,CLASS_9,SECTION_A,TODAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        old.setEffectiveUntil(TODAY.minusDays(1));
        assertThatThrownBy(()->service.createForExplicitReadmission(
                SCHOOL,"S1",SESSION,CLASS_10,SECTION_A,TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Section");
    }

    @Test void lifecycleLockOrderIsStudentThenAllEnrollmentHistory(){
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(active()));
        service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,StudentEnrollmentClosureReason.WITHDRAWN);
        InOrder order=inOrder(students,enrollments);
        order.verify(students).findByStudentIdAndSchoolIdForUpdate("S1",SCHOOL);
        order.verify(enrollments).findAllHistoryForUpdate(SCHOOL,"S1");
    }

    @Test void legacyUncoveredLifecycleDoesNotFabricateEnrollment(){
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of());
        var exit=service.closeForExplicitExit(SCHOOL,"S1",null,TODAY,
                StudentEnrollmentClosureReason.WITHDRAWN);
        assertThat(exit.legacyUncovered()).isTrue();
        assertThat(exit.enrollment()).isNull();
        student.setStatus(StudentStatus.WITHDRAWN);
        var readmit=service.createForExplicitReadmission(SCHOOL,"S1",null,null,null,TODAY);
        assertThat(readmit.legacyUncovered()).isTrue();
        assertThat(readmit.enrollment()).isNull();
        verify(enrollments,never()).saveAndFlush(any());
    }

    @Test void enrollmentBackedReadmissionRequiresACoveringSession(){
        student.setStatus(StudentStatus.WITHDRAWN);
        StudentEnrollment old=active(); old.setStatus(StudentEnrollmentStatus.CLOSED);
        old.setEffectiveUntil(TODAY.minusDays(1)); old.setClosureReason(StudentEnrollmentClosureReason.WITHDRAWN);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(old));
        assertThatThrownBy(()->service.createForExplicitReadmission(
                SCHOOL,"S1",null,CLASS_9,SECTION_A,TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessionId");
    }

    @Test void scheduledActivationChangesEnrollmentStudentStatusAndProjection(){
        student.setStatus(StudentStatus.UPCOMING);
        StudentEnrollment planned=planned(TODAY); planned.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(planned));
        var result=service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY);
        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED);
        assertThat(planned.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(student.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(student.getClassId()).isEqualTo(CLASS_10);
        assertThat(student.getSectionId()).isEqualTo(SECTION_B);
    }

    @Test void scheduledActivationSupportsLateDatesButNotFuturePlans(){
        student.setStatus(StudentStatus.UPCOMING);
        StudentEnrollment late=planned(TODAY.minusDays(2)); late.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(late));
        assertThat(service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY).outcome())
                .isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED);

        student.setStatus(StudentStatus.UPCOMING);
        StudentEnrollment future=planned(TODAY.plusDays(1)); future.setId(201L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(future));
        assertThat(service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY).outcome())
                .isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.NO_PLANNED_ENROLLMENT);
        assertThat(future.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
    }

    @Test void duplicateScheduledActivationIsIdempotent(){
        student.setStatus(StudentStatus.ACTIVE);
        StudentEnrollment active=active(); active.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(active));
        assertThat(service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY).outcome())
                .isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.ALREADY_ACTIVE);
        verify(enrollments,never()).saveAndFlush(any());
    }

    @Test void scheduledActivationRejectsConflictAndInvalidMembership(){
        student.setStatus(StudentStatus.UPCOMING);
        StudentEnrollment planned=planned(TODAY); planned.setId(200L);
        StudentEnrollment active=active(); active.setId(201L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(planned,active));
        assertThatThrownBy(()->service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");

        planned.setClassId(99L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(planned));
        assertThatThrownBy(()->service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY))
                .isInstanceOf(NoSuchElementException.class).hasMessageContaining("Class");
    }

    // ─── E3: continuing-student activation ──────────────────────────────────────────────

    static final long SOURCE_SESSION=9;

    /** A CLOSED SESSION_COMPLETED source segment contiguous with SESSION (the target),
     *  in CLASS_9 — the legitimate year-end-history evidence validateContinuingActivation
     *  requires before a continuing ACTIVE student's PLANNED target may activate. */
    StudentEnrollment closedSource(LocalDate effectiveUntil){
        StudentEnrollment e=active();
        e.setId(150L); e.setAcademicSessionId(SOURCE_SESSION);
        e.setStatus(StudentEnrollmentStatus.CLOSED);
        e.setEffectiveUntil(effectiveUntil);
        e.setClosureReason(StudentEnrollmentClosureReason.SESSION_COMPLETED);
        return e;
    }

    void stubClassSequenceAndSourceSession(){
        when(classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(SCHOOL,true))
                .thenReturn(List.of(schoolClass(CLASS_9,"9"), schoolClass(CLASS_10,"10")));
        AcademicSession sourceSession=new AcademicSession();
        sourceSession.setId(SOURCE_SESSION); sourceSession.setSchoolId(SCHOOL);
        sourceSession.setStartDate(LocalDate.of(2025,7,1)); sourceSession.setEndDate(LocalDate.of(2026,6,30));
        lenient().when(sessions.findByIdAndSchoolId(SOURCE_SESSION,SCHOOL)).thenReturn(Optional.of(sourceSession));
    }

    @Test void continuingActivationPromotesProjectionAndKeepsStudentActive(){
        stubClassSequenceAndSourceSession();
        student.setStatus(StudentStatus.ACTIVE);
        // planned() defaults to CLASS_10/SECTION_B — the immediate successor of source CLASS_9.
        StudentEnrollment target=planned(LocalDate.of(2026,7,1)); target.setId(200L);
        StudentEnrollment source=closedSource(LocalDate.of(2026,6,30));
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(source,target));

        var result=service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY);

        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.CONTINUING_ACTIVATED);
        assertThat(target.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(student.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(student.getClassId()).isEqualTo(CLASS_10);
        assertThat(student.getSectionId()).isEqualTo(SECTION_B);
    }

    @Test void continuingActivationRejectsAnArbitraryPlannedRowWithNoYearEndSourceHistory(){
        student.setStatus(StudentStatus.ACTIVE);
        // No CLOSED SESSION_COMPLETED source at all — an ACTIVE student with a corrupt/
        // arbitrary PLANNED row must never activate on dates alone.
        StudentEnrollment target=planned(LocalDate.of(2026,7,1)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));

        assertThatThrownBy(()->service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one completed source enrollment");
    }

    @Test void continuingActivationRejectsATargetClassThatIsNeitherSameNorImmediateSuccessor(){
        stubClassSequenceAndSourceSession();
        student.setStatus(StudentStatus.ACTIVE);
        // Target class equals the source's own class id family but is corrupted to skip a
        // grade — not DETAIN (same class) and not PROMOTE (immediate successor).
        StudentEnrollment target=planned(LocalDate.of(2026,7,1)); target.setId(200L);
        target.setClassId(99L); target.setClassNameSnapshot("12");
        StudentEnrollment source=closedSource(LocalDate.of(2026,6,30));
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(source,target));

        assertThatThrownBy(()->service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source class")
                .hasMessageContaining("immediate successor");
    }

    @Test void activationAfterTargetSessionEndIsReportedNotThrown(){
        student.setStatus(StudentStatus.UPCOMING);
        AcademicSession expiredSession=new AcademicSession();
        expiredSession.setId(11L); expiredSession.setSchoolId(SCHOOL);
        expiredSession.setStartDate(LocalDate.of(2026,6,1)); expiredSession.setEndDate(LocalDate.of(2026,8,31));
        when(sessions.findByIdAndSchoolId(11L,SCHOOL)).thenReturn(Optional.of(expiredSession));
        StudentEnrollment planned=planned(LocalDate.of(2026,6,15)); planned.setId(200L);
        planned.setAcademicSessionId(11L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(planned));

        // TODAY (2026-09-06) is after the target session's end date (2026-08-31).
        var result=service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY);

        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.EXPIRED_TARGET_SESSION);
        assertThat(planned.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        verify(enrollments,never()).saveAndFlush(any());
    }

    @Test void exitedStudentCandidateIsReportedNotEligibleRatherThanThrown(){
        student.setStatus(StudentStatus.WITHDRAWN);
        StudentEnrollment stalePlanned=planned(TODAY); stalePlanned.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(stalePlanned));

        var result=service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY);

        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.NOT_ELIGIBLE);
        verify(enrollments,never()).saveAndFlush(any());
    }

    @Test void dueGraduationDiscoveryExcludesStudentsAlreadyFinalized(){
        StudentEnrollment finalizedRow=new StudentEnrollment();
        finalizedRow.setStudentId("S1"); finalizedRow.setSchoolId(SCHOOL);
        finalizedRow.setStatus(StudentEnrollmentStatus.CLOSED);
        finalizedRow.setClosureReason(StudentEnrollmentClosureReason.GRADUATED);
        finalizedRow.setEffectiveUntil(TODAY);
        StudentEnrollment pendingRow=new StudentEnrollment();
        pendingRow.setStudentId("S2"); pendingRow.setSchoolId(SCHOOL);
        pendingRow.setStatus(StudentEnrollmentStatus.CLOSED);
        pendingRow.setClosureReason(StudentEnrollmentClosureReason.GRADUATED);
        pendingRow.setEffectiveUntil(TODAY);
        when(enrollments.findBySchoolIdAndStatusAndClosureReasonAndEffectiveUntilLessThanEqualOrderByStudentIdAscEffectiveUntilAsc(
                SCHOOL,StudentEnrollmentStatus.CLOSED,StudentEnrollmentClosureReason.GRADUATED,TODAY))
                .thenReturn(List.of(finalizedRow,pendingRow));

        Student finalizedStudent=student(); finalizedStudent.setStudentId("S1");
        finalizedStudent.setStatus(StudentStatus.GRADUATED); finalizedStudent.setLeavingDate(TODAY);
        Student pendingStudent=student(); pendingStudent.setStudentId("S2");
        pendingStudent.setStatus(StudentStatus.ACTIVE); // e.g. a readmission after graduation
        when(students.findByStudentIdInAndSchoolId(List.of("S1","S2"),SCHOOL))
                .thenReturn(List.of(finalizedStudent,pendingStudent));

        var due=service.findDueGraduationEnrollments(SCHOOL,TODAY);

        assertThat(due).extracting(StudentEnrollment::getStudentId).containsExactly("S2");
    }

    @Test void scheduledActivationLockOrderIsStudentThenAllEnrollmentHistory(){
        student.setStatus(StudentStatus.UPCOMING);
        StudentEnrollment planned=planned(TODAY); planned.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(planned));

        service.activateEligiblePlannedEnrollment(SCHOOL,"S1",TODAY);

        InOrder order=inOrder(students,enrollments);
        order.verify(students).findByStudentIdAndSchoolIdForUpdate("S1",SCHOOL);
        order.verify(enrollments).findAllHistoryForUpdate(SCHOOL,"S1");
    }

    // ─── E5B0: correcting a future PLANNED enrollment ───────────────────────────────────

    @Test void correctsFuturePlannedClassAndSectionInPlaceWithoutTouchingProjection(){
        long originalStudentClassId=student.getClassId();
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L); // defaults to CLASS_10/SECTION_B
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,CLASS_9,true))
                .thenReturn(List.of(section(SECTION_A,CLASS_9,"A")));

        StudentEnrollment corrected=service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_A);

        assertThat(corrected).isSameAs(target);
        assertThat(corrected.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        assertThat(corrected.getClassId()).isEqualTo(CLASS_9);
        assertThat(corrected.getClassNameSnapshot()).isEqualTo("9");
        assertThat(corrected.getSectionId()).isEqualTo(SECTION_A);
        assertThat(corrected.getSectionNameSnapshot()).isEqualTo("A");
        // Nothing is effective yet — the live projection must be untouched.
        assertThat(student.getClassId()).isEqualTo(originalStudentClassId);
        verify(students,never()).save(any());
    }

    @Test void correctsSectionOnlyLeavingClassUnchanged(){
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,CLASS_10,true))
                .thenReturn(List.of(section(32,CLASS_10,"C")));

        StudentEnrollment corrected=service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_10,32L);

        assertThat(corrected.getClassId()).isEqualTo(CLASS_10);
        assertThat(corrected.getSectionId()).isEqualTo(32L);
        assertThat(corrected.getSectionNameSnapshot()).isEqualTo("C");
    }

    @Test void clearsSectionWhenCorrectedClassHasNoSections(){
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        SchoolClass sectionless=schoolClass(40L,"11");
        when(classes.findByIdAndSchoolId(40L,SCHOOL)).thenReturn(Optional.of(sectionless));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,40L,true)).thenReturn(List.of());

        StudentEnrollment corrected=service.correctPlannedEnrollment(SCHOOL,"S1",200L,40L,null);

        assertThat(corrected.getClassId()).isEqualTo(40L);
        assertThat(corrected.getSectionId()).isNull();
        assertThat(corrected.getSectionNameSnapshot()).isNull();
    }

    @Test void rejectsASectionWhenTheCorrectedClassHasNoSections(){
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(classes.findByIdAndSchoolId(40L,SCHOOL)).thenReturn(Optional.of(schoolClass(40L,"11")));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,40L,true)).thenReturn(List.of());

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,"S1",200L,40L,99L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no sections");
    }

    @Test void requiresAnExplicitSectionWhenTheCorrectedClassHasSections(){
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,CLASS_10,true))
                .thenReturn(List.of(section(SECTION_B,CLASS_10,"B")));

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_10,null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explicit target section");
    }

    @Test void rejectsAnInvalidClassOrCrossClassSection(){
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(classes.findByIdAndSchoolId(99L,SCHOOL)).thenReturn(Optional.empty());

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,"S1",200L,99L,null))
                .isInstanceOf(NoSuchElementException.class);

        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,CLASS_9,true))
                .thenReturn(List.of(section(SECTION_A,CLASS_9,"A")));
        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_B))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Target section");
    }

    @Test void rejectsAnArbitraryClassJumpWhenTheRowHasRealYearEndProvenance(){
        // The provenance pattern needs the target row to be strictly future relative to the
        // fixed clock (TODAY), unlike E3's already-due activation fixtures — so this uses a
        // bespoke future target session/source session pair rather than stubClassSequenceAndSourceSession().
        when(classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(SCHOOL,true))
                .thenReturn(List.of(schoolClass(CLASS_9,"9"), schoolClass(CLASS_10,"10")));
        session.setStartDate(TODAY.plusDays(30));
        AcademicSession sourceSession=new AcademicSession();
        sourceSession.setId(SOURCE_SESSION); sourceSession.setSchoolId(SCHOOL);
        sourceSession.setStartDate(TODAY.minusYears(1)); sourceSession.setEndDate(TODAY.plusDays(29));
        lenient().when(sessions.findByIdAndSchoolId(SOURCE_SESSION,SCHOOL)).thenReturn(Optional.of(sourceSession));

        StudentEnrollment target=planned(TODAY.plusDays(30)); target.setId(200L); // == targetSession.startDate
        StudentEnrollment source=closedSource(TODAY.plusDays(29)); // contiguous: sourceSession.endDate
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(source,target));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,CLASS_9,true))
                .thenReturn(List.of(section(SECTION_A,CLASS_9,"A")));

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,"S1",200L,99L,null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source class").hasMessageContaining("immediate successor");
        // The DETAIN-shape correction (same class as the source) must still be accepted.
        StudentEnrollment corrected=service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_A);
        assertThat(corrected.getClassId()).isEqualTo(CLASS_9);
    }

    @Test void allowsAnyValidClassWhenThePlannedRowHasNoYearEndProvenance(){
        // A plain admission-flow PLANNED row — no preceding CLOSED SESSION_COMPLETED sibling —
        // carries no class-sequence constraint; any class the school actually has is acceptable.
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(classes.findByIdAndSchoolId(99L,SCHOOL)).thenReturn(Optional.of(schoolClass(99L,"12")));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,99L,true)).thenReturn(List.of());

        StudentEnrollment corrected=service.correctPlannedEnrollment(SCHOOL,"S1",200L,99L,null);

        assertThat(corrected.getClassId()).isEqualTo(99L);
    }

    @Test void rejectsCorrectionOnceTheEffectiveDateHasArrived(){
        StudentEnrollment dueToday=planned(TODAY); dueToday.setId(200L); // effectiveFrom == today, no longer strictly future
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(dueToday));

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_A))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("future");
    }

    @Test void rejectsCorrectingAnEnrollmentThatIsNoLongerPlanned(){
        StudentEnrollment alreadyActive=planned(TODAY.plusDays(10)); alreadyActive.setId(200L);
        alreadyActive.setStatus(StudentEnrollmentStatus.ACTIVE);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(alreadyActive));

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_A))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PLANNED");
    }

    @Test void exactRepeatCorrectionIsIdempotent(){
        // target defaults to CLASS_10/SECTION_B — correct it to CLASS_9/SECTION_A first so the
        // repeat call genuinely exercises the idempotent short-circuit, not a same-as-default no-op.
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,CLASS_9,true))
                .thenReturn(List.of(section(SECTION_A,CLASS_9,"A")));

        StudentEnrollment first=service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_A);
        StudentEnrollment second=service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_A);

        assertThat(second).isSameAs(first);
        verify(enrollments,times(1)).saveAndFlush(any());
    }

    @Test void correctionLockOrderIsStudentThenAllEnrollmentHistory(){
        StudentEnrollment target=planned(TODAY.plusDays(10)); target.setId(200L);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(target));
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL,CLASS_9,true))
                .thenReturn(List.of(section(SECTION_A,CLASS_9,"A")));

        service.correctPlannedEnrollment(SCHOOL,"S1",200L,CLASS_9,SECTION_A);

        InOrder order=inOrder(students,enrollments);
        order.verify(students).findByStudentIdAndSchoolIdForUpdate("S1",SCHOOL);
        order.verify(enrollments).findAllHistoryForUpdate(SCHOOL,"S1");
    }

    @Test void correctionNeverTouchesFeeOrPaymentRepositories(){
        assertThat(List.of(StudentEnrollmentService.class.getDeclaredFields()))
                .extracting(java.lang.reflect.Field::getType)
                .noneMatch(t->t.getSimpleName().contains("Fee")||t.getSimpleName().contains("Payment"));
    }

    // ─── E5B0: explicit exit auto-cancels a future PLANNED enrollment ───────────────────

    @Test void explicitExitCancelsAFutureUnstartedPlannedEnrollmentAtomically(){
        StudentEnrollment current=active();
        StudentEnrollment futurePlanned=planned(TODAY.plusDays(30)); futurePlanned.setId(200L);
        futurePlanned.setAcademicSessionId(SESSION+1);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(current,futurePlanned));

        var result=service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,StudentEnrollmentClosureReason.WITHDRAWN);

        assertThat(result.enrollment().getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
        assertThat(futurePlanned.getStatus()).isEqualTo(StudentEnrollmentStatus.CANCELLED);
        assertThat(futurePlanned.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.CANCELLED_BEFORE_START);
        assertThat(futurePlanned.getEffectiveUntil()).isEqualTo(futurePlanned.getEffectiveFrom());
        assertThat(result.cancelledFuturePlannedEnrollmentId()).isEqualTo(200L);
        // The cancelled row is saved (persisted as audit evidence), never deleted.
        verify(enrollments).saveAndFlush(futurePlanned);
    }

    @Test void explicitExitNeverCancelsAnAlreadyEffectiveLaterSegment(){
        StudentEnrollment current=active();
        StudentEnrollment laterActive=active(); laterActive.setId(300L);
        laterActive.setAcademicSessionId(SESSION+1);
        laterActive.setEffectiveFrom(TODAY.plusDays(5)); // ACTIVE, not PLANNED — corrupt/unsupported shape
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(current,laterActive));

        assertThatThrownBy(()->service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,StudentEnrollmentClosureReason.WITHDRAWN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("later enrollment segment");
        assertThat(laterActive.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        verify(enrollments,never()).saveAndFlush(laterActive);
    }

    @Test void explicitExitFailsSafelyRatherThanGuessingWithMultipleFutureSegments(){
        StudentEnrollment current=active();
        StudentEnrollment future1=planned(TODAY.plusDays(30)); future1.setId(200L); future1.setAcademicSessionId(SESSION+1);
        StudentEnrollment future2=planned(TODAY.plusDays(400)); future2.setId(201L); future2.setAcademicSessionId(SESSION+2);
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(current,future1,future2));

        assertThatThrownBy(()->service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,StudentEnrollmentClosureReason.WITHDRAWN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("later enrollment segment");
        assertThat(future1.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        assertThat(future2.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
    }

    @Test void explicitExitWithNoFutureSegmentBehavesExactlyAsBefore(){
        StudentEnrollment current=active();
        when(enrollments.findAllHistoryForUpdate(SCHOOL,"S1")).thenReturn(List.of(current));

        var result=service.closeForExplicitExit(SCHOOL,"S1",SESSION,TODAY,StudentEnrollmentClosureReason.WITHDRAWN);

        assertThat(result.cancelledFuturePlannedEnrollmentId()).isNull();
        assertThat(result.enrollment().getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
    }

    Student student(){Student s=new Student();s.setStudentId("S1");s.setSchoolId(SCHOOL);s.setStatus(StudentStatus.ACTIVE);s.setClassId(CLASS_9);s.setClassName("9");s.setSectionId(SECTION_A);s.setSectionName("A");return s;}
    AcademicSession session(){AcademicSession a=new AcademicSession();a.setId(SESSION);a.setSchoolId(SCHOOL);a.setStartDate(LocalDate.of(2026,7,1));a.setEndDate(LocalDate.of(2027,6,30));a.setLabel("2026-2027");return a;}
    SchoolClass schoolClass(long id,String n){SchoolClass c=new SchoolClass();c.setId(id);c.setSchoolId(SCHOOL);c.setName(n);return c;}
    Section section(long id,long classId,String n){Section s=new Section();s.setId(id);s.setSchoolId(SCHOOL);s.setClassId(classId);s.setName(n);return s;}
    StudentEnrollment active(){StudentEnrollment e=new StudentEnrollment();e.setId(100L);e.setSchoolId(SCHOOL);e.setStudentId("S1");e.setAcademicSessionId(SESSION);e.setClassId(CLASS_9);e.setClassNameSnapshot("9");e.setSectionId(SECTION_A);e.setSectionNameSnapshot("A");e.setStatus(StudentEnrollmentStatus.ACTIVE);e.setEffectiveFrom(LocalDate.of(2026,9,1));return e;}
    StudentEnrollment planned(LocalDate from){StudentEnrollment e=active();e.setClassId(CLASS_10);e.setClassNameSnapshot("10");e.setSectionId(SECTION_B);e.setSectionNameSnapshot("B");e.setStatus(StudentEnrollmentStatus.PLANNED);e.setEffectiveFrom(from);return e;}
}
