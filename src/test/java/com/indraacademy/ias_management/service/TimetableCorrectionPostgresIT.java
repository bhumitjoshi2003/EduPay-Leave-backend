package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.dto.TimetableDtos.TimetableEntryRequest;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({TimetableService.class, TimetableCorrectionService.class, TimetableValidationService.class,
         TimetableSessionAccessService.class, TeacherClassScopeService.class, TimetableCorrectionPostgresIT.Config.class})
@EnabledIfEnvironmentVariable(named="DB_URL",matches=".+")
class TimetableCorrectionPostgresIT {
    @org.springframework.boot.test.context.TestConfiguration static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("DB_URL"));r.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @MockitoBean SecurityUtil security;
    @MockitoBean AuditService audit;
    @MockitoBean BusinessNotificationService notifications;
    @Autowired TimetableService timetable;
    @Autowired TimetableCorrectionService corrections;
    @Autowired TimetableRepository entries;
    @Autowired TimetableCorrectionRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    static final long SCHOOL=-97501, SESSION=-97502, CLASS=-97503;
    Long mathId, biologyId;
    HttpServletRequest http=mock(HttpServletRequest.class);
    @BeforeEach void fixtures() {
        when(security.getSchoolId()).thenReturn(SCHOOL);when(security.getRole()).thenReturn("ADMIN");when(security.getUsername()).thenReturn("admin");
        jdbc.update("INSERT INTO school(id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day) VALUES (?,true,CURRENT_TIMESTAMP,'Correction IT','TRIAL','correction-it',4,8)",SCHOOL);
        jdbc.update("INSERT INTO academic_session(id,created_at,is_current,start_date,end_date,label,school_id) VALUES (?,CURRENT_TIMESTAMP,true,DATE '2026-04-01',DATE '2027-03-31','Correction IT',?)",SESSION,SCHOOL);
        jdbc.update("INSERT INTO school_class(id,active,name,school_id,stream_eligible) VALUES (?,true,'11 Science',?,false)",CLASS,SCHOOL);
        for(String id:new String[]{"A","B","C","D"}) jdbc.update("INSERT INTO teacher(teacher_id,school_id,name,status,class_teacher) VALUES (?,?,?,'ACTIVE','11 Science')","CORR-"+id,SCHOOL,"Teacher "+id);
        mathId=entry("Math","CORR-A");biologyId=entry("Biology","CORR-C");
    }
    Long entry(String subject,String teacher) {
        TimetableEntry e=new TimetableEntry();e.setSchoolId(SCHOOL);e.setAcademicSessionId(SESSION);e.setClassId(CLASS);e.setClassName("11 Science");
        e.setDay(Day.TUESDAY);e.setPeriodNumber(4);e.setStartTime("11:00");e.setEndTime("12:00");e.setSubjectName(subject);e.setTeacherId(teacher);e.setSimultaneousGroup("G1");
        return entries.saveAndFlush(e).getId();
    }
    Long request() { return corrections.submit(biologyId,"CORR-B",null,"I teach Biology",http).id(); }
    @Test void approvalChangesOnlyExistingBiologyAssignment() {
        Long id=request();corrections.review(id,true,http);em.flush();em.clear();
        TimetableEntry biology=entries.findByIdAndSchoolId(biologyId,SCHOOL).orElseThrow();
        assertThat(biology.getTeacherId()).isEqualTo("CORR-B");assertThat(biology.getSimultaneousGroup()).isEqualTo("G1");
        assertThat(biology.getSubjectName()).isEqualTo("Biology");assertThat(biology.getPeriodNumber()).isEqualTo(4);
        assertThat(biology.getAcademicSessionId()).isEqualTo(SESSION);assertThat(biology.getClassId()).isEqualTo(CLASS);
        assertThat(entries.findByIdAndSchoolId(mathId,SCHOOL).orElseThrow().getTeacherId()).isEqualTo("CORR-A");
        assertThat(entries.findByAcademicSessionIdAndSchoolId(SESSION,SCHOOL)).hasSize(2);
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(TimetableCorrectionRequest.Status.APPROVED);
    }
    @Test void sameSubjectConflictDoesNotInsertThirdRow() {
        assertThatThrownBy(()->timetable.create(new TimetableEntryRequest(null,CLASS,null,Day.TUESDAY,4,"11:00","12:00","Biology","CORR-D"),"TEACHER","CORR-B",http)).isInstanceOf(TimetableOwnershipConflict.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timetable_entry WHERE school_id=?",Integer.class,SCHOOL)).isEqualTo(2);
    }
    @Test void duplicatePendingIndexRejectsDirectInsert() {
        Long id=request();
        assertThatThrownBy(()->jdbc.update("INSERT INTO timetable_correction_request(school_id,academic_session_id,timetable_entry_id,requested_by_teacher_id,expected_current_teacher_id,requested_teacher_id,expected_revision,status,created_at) SELECT school_id,academic_session_id,timetable_entry_id,requested_by_teacher_id,expected_current_teacher_id,requested_teacher_id,expected_revision,status,created_at FROM timetable_correction_request WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void deletedEntryRetainsRequestAndNullsReference() {
        Long id=request();timetable.delete(biologyId,SESSION,"TEACHER","CORR-C",http);em.flush();em.clear();
        assertThat(requests.findById(id).orElseThrow().getTimetableEntryId()).isNull();
        assertThatThrownBy(()->corrections.review(id,true,http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void changedPeriodRevisionPreventsApproval() {
        Long id=request();TimetableEntry e=entries.findByIdAndSchoolId(biologyId,SCHOOL).orElseThrow();e.setSubjectName("Advanced Biology");entries.saveAndFlush(e);em.clear();
        assertThatThrownBy(()->corrections.review(id,true,http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT teacher_id FROM timetable_entry WHERE id=?",String.class,biologyId)).isEqualTo("CORR-C");
    }
    @Test void rejectedRequestDoesNotChangeAssignment() { Long id=request();corrections.review(id,false,http);em.flush();assertThat(jdbc.queryForObject("SELECT teacher_id FROM timetable_entry WHERE id=?",String.class,biologyId)).isEqualTo("CORR-C"); }
    @Test void requestingTeacherMustMatchRequesterAtDatabaseBoundary() {
        Long id=request();assertThatThrownBy(()->jdbc.update("UPDATE timetable_correction_request SET requested_teacher_id='CORR-D' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void tenantForeignKeyRejectsWrongSchoolTeacher() {
        Long id=request();assertThatThrownBy(()->jdbc.update("UPDATE timetable_correction_request SET expected_current_teacher_id='missing-teacher' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @AfterEach void cleanupCommittedFixtures() {
        if (!org.springframework.test.context.transaction.TestTransaction.isActive()) {
            jdbc.update("DELETE FROM timetable_correction_request WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM timetable_entry WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM teacher WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM school_class WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM academic_session WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM school WHERE id=?",SCHOOL);
        }
    }
    @Test
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void notificationFailureRollsBackApprovalAndAssignmentTogether() {
        Long id=request();
        doThrow(new IllegalStateException("publication failed")).when(notifications).publish(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->corrections.review(id,true,http)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT teacher_id FROM timetable_entry WHERE id=?",String.class,biologyId)).isEqualTo("CORR-C");
        assertThat(jdbc.queryForObject("SELECT status FROM timetable_correction_request WHERE id=?",String.class,id)).isEqualTo("PENDING");
    }
}
