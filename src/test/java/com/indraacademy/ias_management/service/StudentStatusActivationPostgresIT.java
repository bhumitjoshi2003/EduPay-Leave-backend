package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.ClockConfig;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.test.database.replace=none"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentEnrollmentService.class, ClockConfig.class})
@EnabledIfEnvironmentVariable(named="DB_URL",matches=".+")
class StudentStatusActivationPostgresIT {
    static final long SCHOOL=-99001, OTHER_SCHOOL=-99002, SESSION=-99003, CLASS_ID=-99004, SECTION=-99005,
            SOURCE_SESSION=-99006, SOURCE_CLASS=-99007, SOURCE_SECTION=-99008, SKIP_CLASS=-99009;
    static final String STUDENT="STATUS-ACTIVATION-PG";
    static final LocalDate TODAY=LocalDate.of(2026,9,6);
    @Autowired JdbcTemplate jdbc;
    @Autowired StudentEnrollmentService service;
    @Autowired StudentEnrollmentRepository enrollments;
    @Autowired StudentRepository students;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
    }

    @BeforeEach void fixtures(){
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES (?,true,CURRENT_TIMESTAMP,'Activation IT','TRIAL','activation-it',4,8,'Asia/Kolkata'),(?,true,CURRENT_TIMESTAMP,'Other Activation IT','TRIAL','other-activation-it',4,8,'America/Los_Angeles')",SCHOOL,OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?, '2025-2026',DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP),(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',false,CURRENT_TIMESTAMP)",SOURCE_SESSION,SCHOOL,SESSION,SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?,'9',true,1,false),(?,?,'10',true,2,false)",SOURCE_CLASS,SCHOOL,CLASS_ID,SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'Z',true),(?,?,?,'A',true)",SOURCE_SECTION,SCHOOL,SOURCE_CLASS,SECTION,SCHOOL,CLASS_ID);
        insertStudent(STUDENT,TODAY);
        insertPlanned(STUDENT,TODAY);
    }

    @Test void effectiveTodayActivatesExistingRowAndSynchronizesStudentWithoutSideEffects(){
        Long enrollmentId=history(STUDENT).getFirst().getId();
        var result=service.activateEligiblePlannedEnrollment(SCHOOL,STUDENT,TODAY);
        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED);
        assertThat(result.enrollmentId()).isEqualTo(enrollmentId);
        assertThat(history(STUDENT)).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
        var student=students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow();
        assertThat(student.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(student.getClassId()).isEqualTo(CLASS_ID);
        assertThat(student.getSectionId()).isEqualTo(SECTION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE school_id=? AND user_id=?",Integer.class,SCHOOL,STUDENT)).isZero();
    }

    @Test void lateActivationSucceedsAndFutureCandidateRemainsUnchanged(){
        jdbc.update("UPDATE student_enrollment SET effective_from=DATE '2026-09-01' WHERE school_id=?",SCHOOL);
        service.activateEligiblePlannedEnrollment(SCHOOL,STUDENT,TODAY);
        assertThat(history(STUDENT).getFirst().getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);

        String future="STATUS-FUTURE-PG"; insertStudent(future,TODAY.plusDays(1)); insertPlanned(future,TODAY.plusDays(1));
        assertThat(students.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                SCHOOL,StudentStatus.UPCOMING,TODAY)).extracting(s->s.getStudentId()).doesNotContain(future);
        assertThat(history(future).getFirst().getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
    }

    @Test void legacyUncoveredIsReportedAndNotFabricated(){
        String legacy="STATUS-LEGACY-PG"; insertStudent(legacy,TODAY);
        var result=service.activateEligiblePlannedEnrollment(SCHOOL,legacy,TODAY);
        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.NO_PLANNED_ENROLLMENT);
        assertThat(students.findByStudentIdAndSchoolId(legacy,SCHOOL).orElseThrow().getStatus()).isEqualTo(StudentStatus.UPCOMING);
        assertThat(history(legacy)).isEmpty();
    }

    @Test void candidateSelectionIsTenantScopedAndNeverSelectsActiveForDowngrade(){
        jdbc.update("UPDATE student SET status='ACTIVE' WHERE school_id=? AND student_id=?",SCHOOL,STUDENT);
        assertThat(students.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                SCHOOL,StudentStatus.UPCOMING,TODAY)).isEmpty();
        assertThat(students.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                OTHER_SCHOOL,StudentStatus.UPCOMING,TODAY)).isEmpty();
    }

    @Test void duplicateConcurrentActivationIsOneLogicalActivation() throws Exception {
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<StudentEnrollmentService.ScheduledActivationOutcome> call=()->{
                start.await(10,TimeUnit.SECONDS);
                return service.activateEligiblePlannedEnrollment(SCHOOL,STUDENT,TODAY).outcome();
            };
            Future<StudentEnrollmentService.ScheduledActivationOutcome> a=pool.submit(call), b=pool.submit(call);
            start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED,
                            StudentEnrollmentService.ScheduledActivationOutcome.ALREADY_ACTIVE);
            assertThat(history(STUDENT)).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
            assertThat(students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow().getStatus()).isEqualTo(StudentStatus.ACTIVE);
        } finally {
            pool.shutdownNow(); cleanupCommittedFixtures();
        }
    }

    @Test void continuingStudentActivatesDueYearEndTargetAndSynchronizesProjection(){
        String id="STATUS-CONTINUING-PG"; insertContinuing(id,TODAY);
        var result=service.activateEligiblePlannedEnrollment(SCHOOL,id,TODAY);
        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.CONTINUING_ACTIVATED);
        var student=students.findByStudentIdAndSchoolId(id,SCHOOL).orElseThrow();
        assertThat(student.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(student.getClassId()).isEqualTo(CLASS_ID);
        assertThat(student.getSectionId()).isEqualTo(SECTION);
        assertThat(history(id)).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
    }

    @Test void malformedContinuingHistoryIsRejectedWithoutMutation(){
        String id="STATUS-MALFORMED-PG";
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,joining_date) VALUES (?,?,'ACTIVE',?,'9',DATE '2026-04-01')",id,SCHOOL,SOURCE_CLASS);
        insertPlanned(id,LocalDate.of(2026,4,1));
        assertThatThrownBy(()->service.activateEligiblePlannedEnrollment(SCHOOL,id,TODAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("completed source");
        assertThat(history(id).getFirst().getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
    }

    @Test void continuingActivationAfterTargetSessionEndIsRejected(){
        String id="STATUS-EXPIRED-PG"; insertContinuing(id,TODAY);
        // SESSION (the target) ends 2027-03-31; 2027-04-01 is one day past it.
        var result=service.activateEligiblePlannedEnrollment(SCHOOL,id,LocalDate.of(2027,4,1));
        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.EXPIRED_TARGET_SESSION);
        assertThat(history(id).getFirst().getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
        var student=students.findByStudentIdAndSchoolId(id,SCHOOL).orElseThrow();
        assertThat(student.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(student.getClassId()).isEqualTo(SOURCE_CLASS);
    }

    @Test void detainContinuingActivationKeepsSameClassAndActivates(){
        String id="STATUS-DETAIN-PG"; insertContinuingDetain(id);
        var result=service.activateEligiblePlannedEnrollment(SCHOOL,id,TODAY);
        assertThat(result.outcome()).isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.CONTINUING_ACTIVATED);
        var student=students.findByStudentIdAndSchoolId(id,SCHOOL).orElseThrow();
        assertThat(student.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(student.getClassId()).isEqualTo(SOURCE_CLASS);
        assertThat(student.getSectionId()).isEqualTo(SOURCE_SECTION);
        assertThat(history(id)).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
    }

    @Test void continuingActivationRejectsATargetClassThatSkipsAGrade(){
        String id="STATUS-SKIP-PG";
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?,'11',true,3,false)",SKIP_CLASS,SCHOOL);
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,joining_date) VALUES (?,?,'ACTIVE',?,'9',DATE '2026-04-01')",id,SCHOOL,SOURCE_CLASS);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,status,effective_from,effective_until,closure_reason) VALUES (?,?,?,?,?,'CLOSED',DATE '2025-04-01',DATE '2026-03-31','SESSION_COMPLETED')",SCHOOL,id,SOURCE_SESSION,SOURCE_CLASS,"9");
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,'PLANNED',DATE '2026-04-01')",SCHOOL,id,SESSION,SKIP_CLASS,"11");
        assertThatThrownBy(()->service.activateEligiblePlannedEnrollment(SCHOOL,id,TODAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source class").hasMessageContaining("immediate successor");
        assertThat(history(id).getFirst().getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
    }

    @Test void duplicateConcurrentContinuingActivationIsOneLogicalActivation() throws Exception {
        String id="STATUS-CONCURRENT-CONT-PG"; insertContinuing(id,TODAY);
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<StudentEnrollmentService.ScheduledActivationOutcome> call=()->{
                start.await(10,TimeUnit.SECONDS);
                return service.activateEligiblePlannedEnrollment(SCHOOL,id,TODAY).outcome();
            };
            Future<StudentEnrollmentService.ScheduledActivationOutcome> a=pool.submit(call), b=pool.submit(call);
            start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(StudentEnrollmentService.ScheduledActivationOutcome.CONTINUING_ACTIVATED,
                            StudentEnrollmentService.ScheduledActivationOutcome.ALREADY_ACTIVE);
            assertThat(history(id)).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
        } finally {
            pool.shutdownNow(); cleanupCommittedFixtures();
        }
    }

    private void insertStudent(String id,LocalDate joining){jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'UPCOMING',?,'10',?,'A',?)",id,SCHOOL,CLASS_ID,SECTION,joining);}
    private void insertPlanned(String id,LocalDate from){jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,? ,?,'A','PLANNED',?)",SCHOOL,id,SESSION,CLASS_ID,"10",SECTION,from);}
    private void insertContinuing(String id,LocalDate ignored){
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'ACTIVE',?,'9',?,'Z',DATE '2026-04-01')",id,SCHOOL,SOURCE_CLASS,SOURCE_SECTION);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) VALUES (?,?,?,?,?,?,?,'CLOSED',DATE '2025-04-01',DATE '2026-03-31','SESSION_COMPLETED')",SCHOOL,id,SOURCE_SESSION,SOURCE_CLASS,"9",SOURCE_SECTION,"Z");
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'PLANNED',DATE '2026-04-01')",SCHOOL,id,SESSION,CLASS_ID,"10",SECTION,"A");
    }
    private void insertContinuingDetain(String id){
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'ACTIVE',?,'9',?,'Z',DATE '2026-04-01')",id,SCHOOL,SOURCE_CLASS,SOURCE_SECTION);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) VALUES (?,?,?,?,?,?,?,'CLOSED',DATE '2025-04-01',DATE '2026-03-31','SESSION_COMPLETED')",SCHOOL,id,SOURCE_SESSION,SOURCE_CLASS,"9",SOURCE_SECTION,"Z");
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'PLANNED',DATE '2026-04-01')",SCHOOL,id,SESSION,SOURCE_CLASS,"9",SOURCE_SECTION,"Z");
    }
    private List<com.indraacademy.ias_management.entity.StudentEnrollment> history(String id){return enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,id,SESSION);}
    private void cleanupCommittedFixtures(){
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?",SCHOOL); jdbc.update("DELETE FROM student WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?",SCHOOL); jdbc.update("DELETE FROM school_class WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?",SCHOOL); jdbc.update("DELETE FROM school WHERE id IN (?,?)",SCHOOL,OTHER_SCHOOL);
    }
}
