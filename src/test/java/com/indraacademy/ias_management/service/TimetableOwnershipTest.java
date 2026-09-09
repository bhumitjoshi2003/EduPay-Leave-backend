package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.TimetableDtos.TimetableEntryRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.entity.TimetableCorrectionRequest.Status;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TimetableOwnershipTest {
    TimetableRepository entries = mock(TimetableRepository.class);
    TeacherRepository teachers = mock(TeacherRepository.class);
    TimetableCorrectionRequestRepository requests = mock(TimetableCorrectionRequestRepository.class);
    TeacherClassGrantRepository grants = mock(TeacherClassGrantRepository.class);
    TimetableSessionAccessService sessions = mock(TimetableSessionAccessService.class);
    TimetableService timetable = new TimetableService();
    TimetableValidationService validation = new TimetableValidationService();
    TimetableCorrectionService corrections = new TimetableCorrectionService();
    HttpServletRequest http = mock(HttpServletRequest.class);
    BusinessNotificationService notifications = mock(BusinessNotificationService.class);
    Map<String, Teacher> staff = new HashMap<>();
    List<TimetableEntry> rows = new ArrayList<>();
    List<TimetableCorrectionRequest> pending = new ArrayList<>();
    TimetableEntry math, biology;

    void inject(Object target, String field, Object value) { ReflectionTestUtils.setField(target, field, value); }
    @BeforeEach void setup() {
        SecurityUtil security = mock(SecurityUtil.class);
        when(security.getSchoolId()).thenReturn(1L); when(security.getRole()).thenReturn("ADMIN"); when(security.getUsername()).thenReturn("admin");
        AcademicSession session = new AcademicSession(); session.setId(10L); session.setCurrent(true);
        when(sessions.requireCurrentSessionForTeacherWrite(1L)).thenReturn(session);
        when(sessions.currentSessionOrNull(1L)).thenReturn(session);
        when(sessions.requireWritableOwnedSession(1L,10L)).thenReturn(session);
        for (String id : List.of("A","B","C","D")) {
            Teacher teacher = new Teacher(); teacher.setTeacherId(id); teacher.setName("Teacher " + id); teacher.setStatus(TeacherStatus.ACTIVE); staff.put(id,teacher);
        }
        when(teachers.findByTeacherIdAndSchoolId(anyString(),eq(1L))).thenAnswer(i -> Optional.ofNullable(staff.get(i.getArgument(0))));
        SchoolClass sc = new SchoolClass(); sc.setId(11L); sc.setName("11 Science");
        SchoolClassRepository classes = mock(SchoolClassRepository.class);
        when(classes.findByIdAndSchoolId(11L,1L)).thenReturn(Optional.of(sc));
        TeacherClassScopeService scope = mock(TeacherClassScopeService.class);
        when(scope.resolveOwnScope(anyString(),eq(1L))).thenReturn(new TeacherClassScopeService.TeacherScope(null,null,false));
        when(grants.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId(anyString(),eq("11 Science"),isNull(),eq(1L))).thenReturn(true);
        when(entries.findById(anyLong())).thenAnswer(i -> rows.stream().filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
        when(entries.lockById(anyLong(),eq(1L))).thenAnswer(i -> rows.stream().filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
        when(entries.findByIdAndSchoolId(anyLong(),eq(1L))).thenAnswer(i -> rows.stream().filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
        when(entries.findByAcademicSessionIdAndTeacherIdAndSchoolId(anyLong(),anyString(),eq(1L))).thenAnswer(i -> rows.stream().filter(e -> e.getAcademicSessionId().equals(i.getArgument(0)) && e.getTeacherId().equals(i.getArgument(1))).toList());
        when(entries.findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(anyLong(),anyLong(),any(),anyInt(),eq(1L))).thenAnswer(i -> rows.stream().filter(e -> e.getAcademicSessionId().equals(i.getArgument(0)) && e.getClassId().equals(i.getArgument(1)) && e.getDay().equals(i.getArgument(2)) && e.getPeriodNumber().equals(i.getArgument(3))).toList());
        when(entries.findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(anyLong(),anyString(),any(),eq(1L))).thenAnswer(i -> rows.stream().filter(e -> e.getAcademicSessionId().equals(i.getArgument(0)) && e.getTeacherId().equals(i.getArgument(1)) && e.getDay().equals(i.getArgument(2))).toList());
        when(entries.save(any())).thenAnswer(i -> { TimetableEntry e=i.getArgument(0); if(e.getId()==null){e.setId((long)rows.size()+1);rows.add(e);} return e; });
        when(entries.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        doAnswer(i -> { rows.removeIf(e -> e.getId().equals(i.getArgument(0))); return null; }).when(entries).deleteById(anyLong());
        when(requests.saveAndFlush(any())).thenAnswer(i -> { TimetableCorrectionRequest r=i.getArgument(0); if(r.getId()==null){r.setId((long)pending.size()+1); pending.add(r);} return r; });
        when(requests.lockById(anyLong(),eq(1L))).thenAnswer(i -> pending.stream().filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(requests.existsBySchoolIdAndTimetableEntryIdAndRequestedByTeacherIdAndStatus(eq(1L),anyLong(),anyString(),eq(Status.PENDING))).thenAnswer(i -> pending.stream().anyMatch(r -> r.getTimetableEntryId().equals(i.getArgument(1)) && r.getRequestedByTeacherId().equals(i.getArgument(2)) && r.getStatus()==Status.PENDING));
        inject(validation,"timetableRepository",entries);
        inject(timetable,"timetableRepository",entries); inject(timetable,"teacherRepository",teachers); inject(timetable,"schoolClassRepository",classes);
        inject(timetable,"sectionRepository",mock(SectionRepository.class)); inject(timetable,"teacherClassScopeService",scope); inject(timetable,"teacherClassGrantRepository",grants);
        inject(timetable,"sessionAccess",sessions); inject(timetable,"timetableValidationService",validation); inject(timetable,"securityUtil",security);
        inject(timetable,"auditService",mock(AuditService.class)); inject(timetable,"objectMapper",new ObjectMapper());
        inject(corrections,"sessions",sessions);
        inject(corrections,"requests",requests); inject(corrections,"entries",entries); inject(corrections,"teachers",teachers); inject(corrections,"timetable",timetable);
        inject(corrections,"validation",validation); inject(corrections,"security",security); inject(corrections,"notifications",notifications); inject(corrections,"audit",mock(AuditService.class)); inject(corrections,"clock",Clock.systemUTC());
        math = row(1L,"Math","A"); biology = row(2L,"Biology","C"); rows.addAll(List.of(math,biology));
    }
    TimetableEntry row(Long id,String subject,String teacher) {
        TimetableEntry e=new TimetableEntry();e.setId(id);e.setSchoolId(1L);e.setAcademicSessionId(10L);e.setClassId(11L);e.setClassName("11 Science");
        e.setDay(Day.TUESDAY);e.setPeriodNumber(4);e.setStartTime("11:00");e.setEndTime("12:00");e.setSubjectName(subject);e.setTeacherId(teacher);e.setSimultaneousGroup("G1");return e;
    }
    TimetableEntryRequest body(String subject,String teacher) { return new TimetableEntryRequest(10L,11L,null,Day.TUESDAY,4,"11:00","12:00",subject,teacher); }
    @Test void exactMathBiologyScenario() {
        assertThatThrownBy(() -> timetable.create(body("Biology","D"),"TEACHER","B",http)).isInstanceOf(TimetableOwnershipConflict.class);
        assertThat(rows).hasSize(2);
        assertThat(corrections.submit(2L,"B",null,"I teach Biology",http).status()).isEqualTo(Status.PENDING);
        assertThat(corrections.review(1L,true,http).status()).isEqualTo(Status.APPROVED);
        assertThat(math.getTeacherId()).isEqualTo("A"); assertThat(biology.getTeacherId()).isEqualTo("B");
        assertThat(biology.getId()).isEqualTo(2L);assertThat(biology.getSimultaneousGroup()).isEqualTo("G1");assertThat(rows).hasSize(2);
        assertThat(biology.getSubjectName()).isEqualTo("Biology");assertThat(biology.getStartTime()).isEqualTo("11:00");
        verify(notifications,times(2)).publish(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void simultaneousCannotBypassSameSubjectConflict() { assertThatThrownBy(() -> timetable.addSimultaneous(1L,null,"bIOLOGY","D","TEACHER","B",http)).isInstanceOf(TimetableOwnershipConflict.class); assertThat(rows).hasSize(2); }
    @Test void differentSimultaneousSubjectStillAllowed() { rows.remove(biology); timetable.addSimultaneous(1L,null,"Biology","D","TEACHER","B",http); assertThat(rows).hasSize(2);assertThat(rows.get(1).getTeacherId()).isEqualTo("B");assertThat(rows.get(1).getSimultaneousGroup()).isEqualTo("G1"); }
    @Test void teacherCreationForcesOwnership() { rows.clear();TimetableEntry e=timetable.create(body("Biology","D"),"TEACHER","B",http);assertThat(e.getTeacherId()).isEqualTo("B"); }
    @Test void teacherUpdatesOwnEntryAndCannotTransferOwnership() { timetable.update(2L,body("Biology","D"),"TEACHER","C",http);assertThat(biology.getTeacherId()).isEqualTo("C"); }
    @Test void teacherDeletesOwnEntry() { timetable.delete(2L,10L,"TEACHER","C",http);assertThat(rows).containsExactly(math); }
    @Test void teacherCannotEditOthers() { assertThatThrownBy(() -> timetable.update(2L,body("Biology","B"),"TEACHER","B",http)).isInstanceOf(SecurityException.class);assertThat(biology.getTeacherId()).isEqualTo("C"); }
    @Test void teacherCannotDeleteOthers() { assertThatThrownBy(() -> timetable.delete(2L,10L,"TEACHER","B",http)).isInstanceOf(SecurityException.class);assertThat(rows).hasSize(2); }
    @Test void historicalUpdateForbidden() { biology.setAcademicSessionId(9L);assertThatThrownBy(() -> timetable.update(2L,body("Biology","C"),"TEACHER","C",http)).isInstanceOf(SecurityException.class); }
    @Test void historicalDeleteForbidden() { biology.setAcademicSessionId(9L);assertThatThrownBy(() -> timetable.delete(2L,9L,"TEACHER","C",http)).isInstanceOf(SecurityException.class); }
    @Test void historicalCorrectionForbidden() { biology.setAcademicSessionId(9L);assertThatThrownBy(() -> corrections.submit(2L,"B",null,null,http)).isInstanceOf(SecurityException.class); }
    @Test void rejectedRequestLeavesAssignment() { corrections.submit(2L,"B",null,null,http);assertThat(corrections.review(1L,false,http).status()).isEqualTo(Status.REJECTED);assertThat(biology.getTeacherId()).isEqualTo("C"); }
    @Test void staleAssignmentPreserved() { corrections.submit(2L,"B",null,null,http);biology.setTeacherId("D");assertThatThrownBy(() -> corrections.review(1L,true,http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThat(biology.getTeacherId()).isEqualTo("D");assertThat(pending.get(0).getStatus()).isEqualTo(Status.PENDING); }
    @Test void staleNonOwnershipEditRejected() { corrections.submit(2L,"B",null,null,http);biology.setRevision(1);assertThatThrownBy(() -> corrections.review(1L,true,http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
    @Test void deletedEntryRejected() { corrections.submit(2L,"B",null,null,http);rows.remove(biology);assertThatThrownBy(() -> corrections.review(1L,true,http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
    @Test void duplicatePendingRejected() { corrections.submit(2L,"B",null,null,http);assertThatThrownBy(() -> corrections.submit(2L,"B",null,null,http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThat(pending).hasSize(1); }
    @Test void cannotRequestForAnotherTeacher() { assertThatThrownBy(() -> corrections.submit(2L,"B","D",null,http)).isInstanceOf(SecurityException.class); }
    @Test void inactiveCannotCreateDespiteGrant() { staff.get("B").setStatus(TeacherStatus.LEFT);assertThatThrownBy(() -> timetable.create(body("Biology","B"),"TEACHER","B",http)).isInstanceOf(SecurityException.class); }
    @Test void inactiveCannotAddSimultaneous() { staff.get("B").setStatus(TeacherStatus.LEFT);assertThatThrownBy(() -> timetable.addSimultaneous(1L,null,"Biology","B","TEACHER","B",http)).isInstanceOf(SecurityException.class); }
    @Test void inactiveCannotUpdateDespiteExistingRelationship() { staff.get("C").setStatus(TeacherStatus.LEFT);assertThatThrownBy(() -> timetable.update(2L,body("Biology","C"),"TEACHER","C",http)).isInstanceOf(SecurityException.class); }
    @Test void inactiveCannotDelete() { staff.get("C").setStatus(TeacherStatus.LEFT);assertThatThrownBy(() -> timetable.delete(2L,10L,"TEACHER","C",http)).isInstanceOf(SecurityException.class); }
    @Test void inactiveCannotRequest() { staff.get("B").setStatus(TeacherStatus.LEFT);assertThatThrownBy(() -> corrections.submit(2L,"B",null,null,http)).isInstanceOf(SecurityException.class); }
    @Test void inactiveReplacementCannotBeApproved() { corrections.submit(2L,"B",null,null,http);staff.get("B").setStatus(TeacherStatus.LEFT);assertThatThrownBy(() -> corrections.review(1L,true,http)).isInstanceOf(SecurityException.class);assertThat(biology.getTeacherId()).isEqualTo("C"); }
    @Test void teacherConflictStillWorks() { rows.clear();rows.add(math);assertThatThrownBy(() -> timetable.addSimultaneous(1L,null,"Biology","A","TEACHER","A",http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("overlapping"); }
    @Test void approvalRechecksTeacherConflict() { corrections.submit(2L,"B",null,null,http);TimetableEntry other=row(3L,"Chemistry","B");other.setClassId(12L);rows.add(other);assertThatThrownBy(() -> corrections.review(1L,true,http)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("overlapping");assertThat(pending.get(0).getStatus()).isEqualTo(Status.PENDING); }
    @Test void crossTenantTargetNotFound() { when(entries.lockById(2L,1L)).thenReturn(Optional.empty());assertThatThrownBy(() -> corrections.submit(2L,"B",null,null,http)).isInstanceOf(NoSuchElementException.class); }
    @Test void grantRevokedBeforeApprovalRejected() { corrections.submit(2L,"B",null,null,http);when(grants.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("B","11 Science",null,1L)).thenReturn(false);assertThatThrownBy(() -> corrections.review(1L,true,http)).isInstanceOf(SecurityException.class); }

    @Test void movingOwnRowCannotManufactureClassAuthorization() {
        SchoolClassRepository classes = (SchoolClassRepository) ReflectionTestUtils.getField(timetable,"schoolClassRepository");
        SchoolClass target = new SchoolClass(); target.setId(12L); target.setName("12");
        when(classes.findByIdAndSchoolId(12L,1L)).thenReturn(Optional.of(target));
        TimetableEntryRequest moved = new TimetableEntryRequest(10L,12L,null,Day.TUESDAY,4,"11:00","12:00","Biology","C");
        assertThatThrownBy(() -> timetable.update(2L,moved,"TEACHER","C",http)).isInstanceOf(SecurityException.class);
        assertThat(biology.getClassId()).isEqualTo(11L);
    }
}
