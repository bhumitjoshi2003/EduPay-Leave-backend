package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentClosureReason;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import com.indraacademy.ias_management.config.ClockConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentEnrollmentService.class, ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentEnrollmentServicePostgresIT {
    static final long SCHOOL=-96001, SESSION=-96002, CLASS_OLD=-96003, CLASS_NEW=-96004;
    static final String STUDENT="ENR-SERVICE-PG";
    static final LocalDate TODAY=LocalDate.of(2026,9,6);
    @Autowired JdbcTemplate jdbc;
    @Autowired StudentEnrollmentService service;
    @Autowired StudentEnrollmentRepository enrollments;
    @Autowired StudentRepository students;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @BeforeEach void fixtures(){
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day) VALUES (?,true,CURRENT_TIMESTAMP,'Enrollment Service IT','TRIAL','enr-service-it',7,8)",SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?, '2026-2027',DATE '2026-07-01',DATE '2027-06-30',false,CURRENT_TIMESTAMP)",SESSION,SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'9',true,false),(?,?,'10',true,false)",CLASS_OLD,SCHOOL,CLASS_NEW,SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (-96005,?,?,'A',true),(-96006,?,?,'B',true)",SCHOOL,CLASS_OLD,SCHOOL,CLASS_NEW);
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name) VALUES (?,?,'ACTIVE',?,'9',-96005,'A')",STUDENT,SCHOOL,CLASS_OLD);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,-96005,'A','ACTIVE',DATE '2026-09-01')",SCHOOL,STUDENT,SESSION,CLASS_OLD,"9");
    }

    @Test void realPostgresTransitionPreservesInclusiveBoundaryAndProjectionAtomicity(){
        var transition=service.transitionClass(SCHOOL,STUDENT,SESSION,CLASS_NEW,-96006L,TODAY);
        enrollments.flush();
        StudentEnrollment old=transition.closedSegment(), replacement=transition.replacementSegment();
        assertThat(old.getEffectiveUntil()).isEqualTo(TODAY.minusDays(1));
        assertThat(replacement.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(service.findEffectiveEnrollment(SCHOOL,STUDENT,SESSION,TODAY.minusDays(1)))
                .get().extracting(StudentEnrollment::getId).isEqualTo(old.getId());
        assertThat(service.findEffectiveEnrollment(SCHOOL,STUDENT,SESSION,TODAY))
                .get().extracting(StudentEnrollment::getId).isEqualTo(replacement.getId());
        Student projected=students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow();
        assertThat(projected.getClassName()).isEqualTo("10");
        assertThat(projected.getSectionName()).isEqualTo("B");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE student_id=? AND school_id=?",Integer.class,STUDENT,SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE student_id=? AND school_id=?",Integer.class,STUDENT,SCHOOL)).isZero();
    }

    @Test void invalidReplacementRollsBackWithoutChangingEnrollmentOrProjection(){
        assertThatThrownBy(()->service.transitionClass(SCHOOL,STUDENT,SESSION,CLASS_NEW,-96005L,TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        StudentEnrollment enrollment=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,SESSION).getFirst();
        assertThat(enrollment.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(enrollment.getEffectiveUntil()).isNull();
        Student projected=students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow();
        assertThat(projected.getClassName()).isEqualTo("9");
        assertThat(projected.getSectionName()).isEqualTo("A");
    }

    @Test void realPostgresProjectionRepairChangesStudentNotEnrollment(){
        jdbc.update("UPDATE student SET class_id=?,class_name='10',section_id=NULL,section_name=NULL WHERE student_id=? AND school_id=?",CLASS_NEW,STUDENT,SCHOOL);
        StudentEnrollment before=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,SESSION).getFirst();
        service.repairProjectionFromEnrollment(SCHOOL,STUDENT,SESSION);
        Student projected=students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow();
        assertThat(projected.getClassName()).isEqualTo("9");
        StudentEnrollment after=enrollments.findById(before.getId()).orElseThrow();
        assertThat(after.getClassId()).isEqualTo(before.getClassId());
        assertThat(after.getClassNameSnapshot()).isEqualTo(before.getClassNameSnapshot());
    }

    // ─── E5B0: correcting a future PLANNED enrollment against a real Postgres schema ────────

    private Long insertUpcomingWithPlanned(String id, LocalDate from){
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'UPCOMING',?,'9',?,'A',?)",id,SCHOOL,CLASS_OLD,-96005L,from);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,'A','PLANNED',?)",SCHOOL,id,SESSION,CLASS_OLD,"9",-96005L,from);
        return enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,id,SESSION).getFirst().getId();
    }

    @Test void correctsFuturePlannedClassAndSectionInDatabaseWithoutTouchingProjection(){
        String id="ENR-CORRECT-PG";
        Long enrollmentId=insertUpcomingWithPlanned(id,TODAY.plusDays(25));

        StudentEnrollment corrected=service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_NEW,-96006L);
        enrollments.flush();

        StudentEnrollment reread=enrollments.findById(enrollmentId).orElseThrow();
        assertThat(reread.getClassId()).isEqualTo(CLASS_NEW);
        assertThat(reread.getSectionId()).isEqualTo(-96006L);
        assertThat(reread.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        assertThat(corrected.getId()).isEqualTo(enrollmentId);
        Student projected=students.findByStudentIdAndSchoolId(id,SCHOOL).orElseThrow();
        assertThat(projected.getClassId()).isEqualTo(CLASS_OLD);
        assertThat(projected.getSectionId()).isEqualTo(-96005L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,id)).isZero();
    }

    @Test void rejectsAnInvalidClassOnCorrectionWithoutMutatingTheRow(){
        String id="ENR-BADCLASS-PG";
        Long enrollmentId=insertUpcomingWithPlanned(id,TODAY.plusDays(25));

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,-999999L,null))
                .isInstanceOf(NoSuchElementException.class);

        StudentEnrollment unchanged=enrollments.findById(enrollmentId).orElseThrow();
        assertThat(unchanged.getClassId()).isEqualTo(CLASS_OLD);
    }

    @Test void rejectsACrossClassSectionOnCorrectionWithoutMutatingTheRow(){
        String id="ENR-BADSECTION-PG";
        Long enrollmentId=insertUpcomingWithPlanned(id,TODAY.plusDays(25));

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_NEW,-96005L))
                .isInstanceOf(IllegalArgumentException.class);

        StudentEnrollment unchanged=enrollments.findById(enrollmentId).orElseThrow();
        assertThat(unchanged.getClassId()).isEqualTo(CLASS_OLD);
        assertThat(unchanged.getSectionId()).isEqualTo(-96005L);
    }

    @Test void rejectsAnArbitraryClassJumpButAllowsDetainShapeWithRealYearEndProvenance(){
        long futureSession=-96010, skipClass=-96011;
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,'2027-2028',DATE '2027-07-01',DATE '2028-06-30',false,CURRENT_TIMESTAMP)",futureSession,SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?,'11',true,3,false)",skipClass,SCHOOL);
        jdbc.update("UPDATE school_class SET display_order=1 WHERE id=?",CLASS_OLD);
        jdbc.update("UPDATE school_class SET display_order=2 WHERE id=?",CLASS_NEW);
        String id="ENR-PROVENANCE-PG";
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'ACTIVE',?,'9',?,'A',DATE '2025-07-01')",id,SCHOOL,CLASS_OLD,-96005L);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) VALUES (?,?,?,?,?,?,'A','CLOSED',DATE '2026-07-01',DATE '2027-06-30','SESSION_COMPLETED')",SCHOOL,id,SESSION,CLASS_OLD,"9",-96005L);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,'B','PLANNED',DATE '2027-07-01')",SCHOOL,id,futureSession,CLASS_NEW,"10",-96006L);
        Long enrollmentId=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,id,futureSession).getFirst().getId();

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,skipClass,null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source class").hasMessageContaining("immediate successor");

        StudentEnrollment corrected=service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_OLD,-96005L);
        assertThat(corrected.getClassId()).isEqualTo(CLASS_OLD);
    }

    @Test void rejectsCorrectionOnceTheEffectiveDateHasArrivedInDatabase(){
        String id="ENR-DUETODAY-PG";
        Long enrollmentId=insertUpcomingWithPlanned(id,TODAY);

        assertThatThrownBy(()->service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_NEW,-96006L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("future");
    }

    @Test void duplicateCorrectionIsIdempotentInDatabase(){
        String id="ENR-DUPLICATE-PG";
        Long enrollmentId=insertUpcomingWithPlanned(id,TODAY.plusDays(25));

        StudentEnrollment first=service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_NEW,-96006L);
        StudentEnrollment second=service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_NEW,-96006L);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,id,SESSION)).hasSize(1);
    }

    @Test void concurrentDuplicateCorrectionIsSerializedToOneLogicalChange() throws Exception {
        String id="ENR-CONCURRENT-CORRECT-PG";
        Long enrollmentId=insertUpcomingWithPlanned(id,TODAY.plusDays(25));
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<StudentEnrollment> call=()->{
                start.await(10,TimeUnit.SECONDS);
                return service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_NEW,-96006L);
            };
            Future<StudentEnrollment> a=pool.submit(call), b=pool.submit(call);
            start.countDown();
            StudentEnrollment ra=a.get(20,TimeUnit.SECONDS), rb=b.get(20,TimeUnit.SECONDS);
            assertThat(ra.getId()).isEqualTo(enrollmentId);
            assertThat(rb.getId()).isEqualTo(enrollmentId);
            List<StudentEnrollment> history=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,id,SESSION);
            assertThat(history).singleElement().satisfies(e->{
                assertThat(e.getClassId()).isEqualTo(CLASS_NEW);
                assertThat(e.getSectionId()).isEqualTo(-96006L);
            });
        } finally {
            pool.shutdownNow(); cleanupCommittedFixture(id);
        }
    }

    @Test void concurrentCorrectionAttemptDuringDueActivationFailsSafely() throws Exception {
        String id="ENR-CORRECT-VS-ACTIVATE-PG";
        Long enrollmentId=insertUpcomingWithPlanned(id,TODAY); // due today: activation-eligible, never correction-eligible
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Object> activate=()->{ start.await(10,TimeUnit.SECONDS); return service.activateEligiblePlannedEnrollment(SCHOOL,id,TODAY); };
            Callable<Object> correct=()->{
                start.await(10,TimeUnit.SECONDS);
                try { return service.correctPlannedEnrollment(SCHOOL,id,enrollmentId,CLASS_NEW,-96006L); }
                catch (IllegalStateException e) { return e; }
            };
            Future<Object> fa=pool.submit(activate), fc=pool.submit(correct);
            start.countDown();
            Object activationResult=fa.get(20,TimeUnit.SECONDS), correctionResult=fc.get(20,TimeUnit.SECONDS);
            assertThat(activationResult).isInstanceOf(StudentEnrollmentService.ScheduledActivation.class);
            assertThat(((StudentEnrollmentService.ScheduledActivation) activationResult).outcome())
                    .isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED);
            // Whichever thread's transaction commits first decides which safe rejection the
            // correction sees: if it runs before activation, the row is still PLANNED but not yet
            // future ("must be future"); if activation commits first, the row is already ACTIVE
            // ("Only a PLANNED enrollment can be corrected"). Either is a clean, non-corrupting failure.
            assertThat(correctionResult).isInstanceOf(IllegalStateException.class);
            assertThat(((IllegalStateException) correctionResult).getMessage())
                    .containsAnyOf("future","PLANNED enrollment can be corrected");
            StudentEnrollment finalRow=enrollments.findById(enrollmentId).orElseThrow();
            assertThat(finalRow.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
            assertThat(finalRow.getClassId()).isEqualTo(CLASS_OLD); // never touched by the failed correction
        } finally {
            pool.shutdownNow(); cleanupCommittedFixture(id);
        }
    }

    private void cleanupCommittedFixture(String id){
        // TestTransaction.flagForCommit() commits the WHOLE transaction, including the
        // @BeforeEach-inserted base fixture (school/session/classes/sections/base student) —
        // not just this test's own dedicated student — so every one of those must be
        // cleaned up too, or the next test's @BeforeEach insert collides on school_pkey.
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=? AND student_id=?",SCHOOL,id);
        jdbc.update("DELETE FROM student WHERE school_id=? AND student_id=?",SCHOOL,id);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM school WHERE id=?",SCHOOL);
    }
}
