package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;

import static com.indraacademy.ias_management.service.StudentYearEndDecision.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StudentYearEndService.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentYearEndServicePostgresIT {
    static final long SCHOOL=-99601, OTHER_SCHOOL=-99602;
    static final long SOURCE_SESSION=-99603, TARGET_SESSION=-99604, GAP_SESSION=-99605;
    static final long CLASS_9=-99606, CLASS_10=-99607, CLASS_11=-99608, OTHER_CLASS=-99609;
    static final long SECTION_9=-99610, SECTION_10=-99611, SECTION_11=-99612, OTHER_SECTION=-99613;
    static final String STUDENT="YEAR-END-PG";
    static final LocalDate SOURCE_END=LocalDate.of(2027,3,31), TARGET_START=LocalDate.of(2027,4,1);
    static final AuditContext ACTOR=new AuditContext("admin","ADMIN","127.0.0.1");

    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StudentYearEndService service;
    @Autowired StudentEnrollmentRepository enrollments;
    @Autowired jakarta.persistence.EntityManager em;
    @MockBean ParentPortalService parentPortal;
    @MockBean AuditService auditService;
    @MockBean Clock clock;
    long sourceId;

    @BeforeEach void fixtures(){
        setToday(LocalDate.of(2027,3,1));
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES (?,true,CURRENT_TIMESTAMP,'Year End IT','TRIAL','year-end-it',4,8,'Asia/Kolkata'),(?,true,CURRENT_TIMESTAMP,'Other Year End IT','TRIAL','other-year-end-it',4,8,'Asia/Kolkata')",SCHOOL,OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?, '2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP),(?,?,'2027-2028',DATE '2027-04-01',DATE '2028-03-31',false,CURRENT_TIMESTAMP),(?,?,'2028-gap',DATE '2028-04-02',DATE '2029-04-01',false,CURRENT_TIMESTAMP)",SOURCE_SESSION,SCHOOL,TARGET_SESSION,SCHOOL,GAP_SESSION,SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?, '9',true,1,false),(?,?,'10',true,2,false),(?,?,'11',true,3,false),(?,?,'9',true,1,false)",CLASS_9,SCHOOL,CLASS_10,SCHOOL,CLASS_11,SCHOOL,OTHER_CLASS,OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active,display_order) VALUES (?,?,?,'A',true,1),(?,?,?,'B',true,1),(?,?,?,'C',true,1),(?,?,?,'X',true,1)",SECTION_9,SCHOOL,CLASS_9,SECTION_10,SCHOOL,CLASS_10,SECTION_11,SCHOOL,CLASS_11,OTHER_SECTION,OTHER_SCHOOL,OTHER_CLASS);
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'ACTIVE',?,'9',?,'A',DATE '2026-04-01')",STUDENT,SCHOOL,CLASS_9,SECTION_9);
        sourceId=jdbc.queryForObject("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'ACTIVE',DATE '2026-04-01') RETURNING id",Long.class,SCHOOL,STUDENT,SOURCE_SESSION,CLASS_9,"9",SECTION_9,"A");
    }

    @Test void earlyPromoteClosesSourceCreatesPlannedAndPreservesProjection(){
        Result result=service.apply(promote(CLASS_10,SECTION_10));
        assertThat(result.outcome()).isEqualTo(Outcome.PROMOTED);
        assertThat(result.targetEnrollmentStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        assertSource("CLOSED",SOURCE_END,"SESSION_COMPLETED");
        assertTarget(CLASS_10,SECTION_10,"PLANNED");
        assertProjection(CLASS_9,SECTION_9,"ACTIVE");
        assertNoFinancialRows();
        verifyNoInteractions(parentPortal);
    }

    @Test void currentPromoteCreatesActiveAndSynchronizesProjection(){
        setToday(TARGET_START);
        Result result=service.apply(promote(CLASS_10,SECTION_10));
        assertThat(result.targetEnrollmentStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertTarget(CLASS_10,SECTION_10,"ACTIVE");
        assertProjection(CLASS_10,SECTION_10,"ACTIVE");
    }

    @Test void arbitraryHigherClassIsRejected(){
        assertThatThrownBy(()->service.apply(promote(CLASS_11,SECTION_11)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("immediate configured successor");
        assertUnchanged();
    }

    @Test void promoteRequiresSectionWhenTargetClassHasSections(){
        assertThatThrownBy(()->service.apply(promote(CLASS_10,null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explicit target section");
        assertUnchanged();
    }

    @Test void crossTenantOrWrongClassSectionIsRejected(){
        assertThatThrownBy(()->service.apply(promote(CLASS_10,OTHER_SECTION)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another school");
        assertUnchanged();
        assertThatThrownBy(()->service.apply(promote(CLASS_10,SECTION_11)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another class");
    }

    @Test void detainCreatesSameClassTargetAndPreservesExactSection(){
        Result result=service.apply(detain(null));
        assertThat(result.outcome()).isEqualTo(Outcome.DETAINED);
        assertSource("CLOSED",SOURCE_END,"SESSION_COMPLETED");
        assertTarget(CLASS_9,SECTION_9,"PLANNED");
        assertProjection(CLASS_9,SECTION_9,"ACTIVE");
        verifyNoInteractions(parentPortal);
    }

    @Test void detainRequiresValidReplacementWhenSourceSectionIsNoLongerActive(){
        jdbc.update("UPDATE section SET active=false WHERE id=?",SECTION_9);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active,display_order) VALUES (-99614,?,?, 'Replacement',true,2)",SCHOOL,CLASS_9);
        assertThatThrownBy(()->service.apply(detain(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inactive");
        assertUnchanged();
    }

    @Test void earlyPassOutRecordsFutureGraduationWithoutChangingStudentOrParents(){
        moveToFinalClass();
        Result result=service.apply(passOut());
        assertThat(result.outcome()).isEqualTo(Outcome.PASSED_OUT);
        assertThat(result.lifecycleFinalizationPending()).isTrue();
        assertSource("CLOSED",SOURCE_END,"GRADUATED");
        assertProjection(CLASS_11,SECTION_11,"ACTIVE");
        assertThat(jdbc.queryForObject("SELECT leaving_date FROM student WHERE school_id=? AND student_id=?",LocalDate.class,SCHOOL,STUDENT)).isNull();
        verifyNoInteractions(parentPortal);
    }

    @Test void effectivePassOutGraduatesAndCutsParentsAtSessionEnd(){
        moveToFinalClass(); setToday(SOURCE_END);
        Result result=service.apply(passOut());
        assertThat(result.lifecycleFinalizationPending()).isFalse();
        assertProjection(CLASS_11,SECTION_11,"GRADUATED");
        assertThat(jdbc.queryForObject("SELECT leaving_date FROM student WHERE school_id=? AND student_id=?",LocalDate.class,SCHOOL,STUDENT)).isEqualTo(SOURCE_END);
        verify(parentPortal).endRelationshipsForExitedStudent(SCHOOL,STUDENT,SOURCE_END);
    }

    @Test void independentFinalizerCompletesDueScheduledGraduation(){
        moveToFinalClass(); service.apply(passOut()); clearInvocations(parentPortal,auditService);
        setToday(SOURCE_END);
        Result result=service.finalizeGraduation(SCHOOL,STUDENT,SOURCE_SESSION,sourceId,ACTOR);
        assertThat(result.outcome()).isEqualTo(Outcome.PASSED_OUT);
        assertProjection(CLASS_11,SECTION_11,"GRADUATED");
        verify(parentPortal).endRelationshipsForExitedStudent(SCHOOL,STUDENT,SOURCE_END);
    }

    @Test void concurrentGraduationFinalizationIsOneLogicalFinalization() throws Exception {
        moveToFinalClass(); service.apply(passOut()); setToday(SOURCE_END);
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Outcome> call=()->{start.await(10,TimeUnit.SECONDS);return service
                    .finalizeGraduation(SCHOOL,STUDENT,SOURCE_SESSION,sourceId,ACTOR).outcome();};
            Future<Outcome> a=pool.submit(call),b=pool.submit(call);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Outcome.PASSED_OUT,Outcome.ALREADY_APPLIED);
            assertProjection(CLASS_11,SECTION_11,"GRADUATED");
        } finally {pool.shutdownNow();cleanupCommittedFixtures();TestTransaction.start();}
    }

    @Test void readmissionAfterFinalizedGraduationPreventsReFinalization(){
        moveToFinalClass(); service.apply(passOut()); setToday(SOURCE_END);
        assertThat(service.finalizeGraduation(SCHOOL,STUDENT,SOURCE_SESSION,sourceId,ACTOR).outcome())
                .isEqualTo(Outcome.PASSED_OUT);
        clearInvocations(parentPortal,auditService);

        // Simulate a readmission recorded after the graduation was finalized: a new open
        // ACTIVE segment in a later session, and the Student projection reset the same way
        // StudentService.readmitStudent does (status back to ACTIVE, leavingDate cleared).
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'ACTIVE',?)",
                SCHOOL,STUDENT,TARGET_SESSION,CLASS_9,"9",SECTION_9,"A",TARGET_START);
        jdbc.update("UPDATE student SET status='ACTIVE', leaving_date=NULL, class_id=?, class_name='9', section_id=?, section_name='A' WHERE school_id=? AND student_id=?",
                CLASS_9,SECTION_9,SCHOOL,STUDENT);

        Result rerun = service.finalizeGraduation(SCHOOL,STUDENT,SOURCE_SESSION,sourceId,ACTOR);

        assertThat(rerun.outcome()).isEqualTo(Outcome.CONFLICT);
        // The readmission must survive completely untouched — no silent reversal back to
        // GRADUATED, no leavingDate reset, no parent cutoff.
        assertProjection(CLASS_9,SECTION_9,"ACTIVE");
        assertThat(jdbc.queryForObject("SELECT leaving_date FROM student WHERE school_id=? AND student_id=?",LocalDate.class,SCHOOL,STUDENT)).isNull();
        verifyNoInteractions(parentPortal);
    }

    @Test void explicitExitWinsRaceWithScheduledGraduation(){
        moveToFinalClass();service.apply(passOut());setToday(SOURCE_END);
        jdbc.update("UPDATE student SET status='WITHDRAWN' WHERE school_id=? AND student_id=?",SCHOOL,STUDENT);
        // The raw JDBC update above bypasses Hibernate entirely — the Student entity loaded by
        // apply() above is still managed in this same transaction's persistence context and
        // would otherwise be returned as-is (still ACTIVE) by finalizeGraduation's own
        // findByStudentIdAndSchoolIdForUpdate, silently masking the very race this test exists
        // to prove is rejected. Clearing forces a fresh read, matching what two genuinely
        // separate transactions (the real-world race this simulates) would see naturally.
        em.clear();
        assertThat(service.finalizeGraduation(SCHOOL,STUDENT,SOURCE_SESSION,sourceId,ACTOR).outcome())
                .isEqualTo(Outcome.CONFLICT);
        assertProjection(CLASS_11,SECTION_11,"WITHDRAWN");
    }

    @Test void passOutRequiresFinalConfiguredClass(){
        Request invalid=new Request(SCHOOL,STUDENT,SOURCE_SESSION,null,sourceId,CLASS_9,Action.PASS_OUT,null,null,ACTOR);
        assertThatThrownBy(()->service.apply(invalid))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("final configured class");
        assertUnchanged();
    }

    @Test void sessionsMustBeContiguousAndTargetMustExist(){
        Request gap=new Request(SCHOOL,STUDENT,SOURCE_SESSION,GAP_SESSION,sourceId,CLASS_9,Action.PROMOTE,CLASS_10,SECTION_10,ACTOR);
        assertThatThrownBy(()->service.apply(gap)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("day after");
        Request missing=new Request(SCHOOL,STUDENT,SOURCE_SESSION,-99999L,sourceId,CLASS_9,Action.PROMOTE,CLASS_10,SECTION_10,ACTOR);
        assertThatThrownBy(()->service.apply(missing)).isInstanceOf(java.util.NoSuchElementException.class).hasMessageContaining("Target session");
        assertUnchanged();
    }

    @Test void legacyUncoveredAndSourceMismatchAreInvalidSource(){
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=? AND student_id=?",SCHOOL,STUDENT);
        assertThat(service.apply(promote(CLASS_10,SECTION_10)).outcome()).isEqualTo(Outcome.INVALID_SOURCE);
        sourceId=insertSource(CLASS_9,SECTION_9);
        Request mismatch=new Request(SCHOOL,STUDENT,SOURCE_SESSION,TARGET_SESSION,sourceId,CLASS_10,Action.PROMOTE,CLASS_10,SECTION_10,ACTOR);
        assertThat(service.apply(mismatch).outcome()).isEqualTo(Outcome.INVALID_SOURCE);
    }

    @Test void exactRerunIsAlreadyAppliedAndDifferentRerunConflicts(){
        service.apply(promote(CLASS_10,SECTION_10));
        assertThat(service.apply(promote(CLASS_10,SECTION_10)).outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
        Request different=new Request(SCHOOL,STUDENT,SOURCE_SESSION,TARGET_SESSION,sourceId,CLASS_9,Action.DETAIN,CLASS_9,null,ACTOR);
        assertThat(service.apply(different).outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isEqualTo(2);
    }

    @Test void passOutRerunIsAlreadyAppliedAndPromoteAfterPassOutConflicts(){
        moveToFinalClass(); service.apply(passOut());
        assertThat(service.apply(passOut()).outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
        Request promote=new Request(SCHOOL,STUDENT,SOURCE_SESSION,TARGET_SESSION,sourceId,CLASS_11,Action.PROMOTE,CLASS_10,SECTION_10,ACTOR);
        assertThat(service.apply(promote).outcome()).isEqualTo(Outcome.CONFLICT);
    }

    @Test void tenantIsolationRejectsForeignSchoolIdentity(){
        Request foreign=new Request(OTHER_SCHOOL,STUDENT,SOURCE_SESSION,TARGET_SESSION,sourceId,CLASS_9,Action.PROMOTE,CLASS_10,SECTION_10,ACTOR);
        assertThatThrownBy(()->service.apply(foreign)).isInstanceOf(java.util.NoSuchElementException.class);
        assertUnchanged();
    }

    @Test void auditFailureRollsBackSourceTargetAndProjection(){
        TestTransaction.flagForCommit(); TestTransaction.end();
        doThrow(new IllegalStateException("audit unavailable")).when(auditService)
                .log(any(),any(),any(),any(),any(),any(),any(),any());
        try {
            assertThatThrownBy(()->service.apply(promote(CLASS_10,SECTION_10)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
            assertUnchanged();
        } finally {
            cleanupCommittedFixtures();
            TestTransaction.start();
        }
    }

    private Request promote(Long targetClass,Long targetSection){return new Request(SCHOOL,STUDENT,SOURCE_SESSION,TARGET_SESSION,sourceId,CLASS_9,Action.PROMOTE,targetClass,targetSection,ACTOR);}
    private Request detain(Long section){return new Request(SCHOOL,STUDENT,SOURCE_SESSION,TARGET_SESSION,sourceId,CLASS_9,Action.DETAIN,CLASS_9,section,ACTOR);}
    private Request passOut(){return new Request(SCHOOL,STUDENT,SOURCE_SESSION,null,sourceId,CLASS_11,Action.PASS_OUT,null,null,ACTOR);}
    private void setToday(LocalDate date){when(clock.withZone(any(ZoneId.class))).thenAnswer(i->Clock.fixed(date.atStartOfDay(i.getArgument(0)).toInstant(),i.getArgument(0)));}
    private long insertSource(long classId,long sectionId){return jdbc.queryForObject("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'ACTIVE',DATE '2026-04-01') RETURNING id",Long.class,SCHOOL,STUDENT,SOURCE_SESSION,classId,String.valueOf(classId==CLASS_9?9:11),sectionId,classId==CLASS_9?"A":"C");}
    private void moveToFinalClass(){jdbc.update("DELETE FROM student_enrollment WHERE id=?",sourceId); jdbc.update("UPDATE student SET class_id=?,class_name='11',section_id=?,section_name='C' WHERE school_id=? AND student_id=?",CLASS_11,SECTION_11,SCHOOL,STUDENT); sourceId=insertSource(CLASS_11,SECTION_11);}
    private void assertSource(String status,LocalDate until,String reason){var m=jdbc.queryForMap("SELECT status,closure_reason FROM student_enrollment WHERE id=?",sourceId);assertThat(m.get("status")).isEqualTo(status);assertThat(jdbc.queryForObject("SELECT effective_until FROM student_enrollment WHERE id=?",LocalDate.class,sourceId)).isEqualTo(until);assertThat(m.get("closure_reason")).isEqualTo(reason);}
    private void assertTarget(long classId,long sectionId,String status){var m=jdbc.queryForMap("SELECT class_id,section_id,status FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",SCHOOL,STUDENT,TARGET_SESSION);assertThat(m.get("class_id")).isEqualTo(classId);assertThat(m.get("section_id")).isEqualTo(sectionId);assertThat(m.get("status")).isEqualTo(status);assertThat(jdbc.queryForObject("SELECT effective_from FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",LocalDate.class,SCHOOL,STUDENT,TARGET_SESSION)).isEqualTo(TARGET_START);}
    private void assertProjection(long classId,long sectionId,String status){var m=jdbc.queryForMap("SELECT class_id,section_id,status FROM student WHERE school_id=? AND student_id=?",SCHOOL,STUDENT);assertThat(m.get("class_id")).isEqualTo(classId);assertThat(m.get("section_id")).isEqualTo(sectionId);assertThat(m.get("status")).isEqualTo(status);}
    private void assertUnchanged(){assertThat(jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isOne();assertSource("ACTIVE",null,null);assertProjection(CLASS_9,SECTION_9,"ACTIVE");}
    private void assertNoFinancialRows(){assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isZero();}
    private void cleanupCommittedFixtures(){jdbc.update("DELETE FROM student_enrollment WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM student WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM section WHERE school_id IN (?,?)",SCHOOL,OTHER_SCHOOL);jdbc.update("DELETE FROM school_class WHERE school_id IN (?,?)",SCHOOL,OTHER_SCHOOL);jdbc.update("DELETE FROM academic_session WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM school WHERE id IN (?,?)",SCHOOL,OTHER_SCHOOL);}
}
