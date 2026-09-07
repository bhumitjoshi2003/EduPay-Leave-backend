package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.Request;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.View;
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
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassTeacherResponsibilityServiceTest {

    @Mock private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Mock private TimetableSessionAccessService sessionAccess;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private ClassTeacherResponsibilityService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CLASS_NO_SECTION_ID = 100L;
    private static final Long CLASS_WITH_SECTION_ID = 101L;
    private static final Long SECTION_ID = 200L;

    @BeforeEach
    void setUp() {
        service = new ClassTeacherResponsibilityService();
        ReflectionTestUtils.setField(service, "responsibilityRepository", responsibilityRepository);
        ReflectionTestUtils.setField(service, "sessionAccess", sessionAccess);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(responsibilityRepository.saveAndFlush(any(ClassTeacherResponsibility.class))).thenAnswer(inv -> {
            ClassTeacherResponsibility r = inv.getArgument(0);
            if (r.getId() == null) r.setId(500L);
            return r;
        });

        AcademicSession session = new AcademicSession();
        session.setId(SESSION_ID);
        session.setCurrent(true);
        lenient().when(sessionAccess.lockWritableOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);
        lenient().when(sessionAccess.requireOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        SchoolClass noSection = schoolClass(CLASS_NO_SECTION_ID, "10");
        lenient().when(schoolClassRepository.findByIdAndSchoolId(CLASS_NO_SECTION_ID, SCHOOL_ID)).thenReturn(Optional.of(noSection));
        lenient().when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_NO_SECTION_ID, true))
                .thenReturn(List.of());

        SchoolClass withSection = schoolClass(CLASS_WITH_SECTION_ID, "11");
        lenient().when(schoolClassRepository.findByIdAndSchoolId(CLASS_WITH_SECTION_ID, SCHOOL_ID)).thenReturn(Optional.of(withSection));
        lenient().when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_WITH_SECTION_ID, true))
                .thenReturn(List.of(new Section()));
        lenient().when(sectionRepository.findByIdAndSchoolId(SECTION_ID, SCHOOL_ID))
                .thenReturn(Optional.of(section(SECTION_ID, CLASS_WITH_SECTION_ID, "A")));

        Teacher active = teacher("T1", TeacherStatus.ACTIVE);
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(active));
        Teacher left = teacher("T-LEFT", TeacherStatus.LEFT);
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T-LEFT", SCHOOL_ID)).thenReturn(Optional.of(left));
        lenient().when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId(any(), any())).thenReturn(List.of());
        lenient().when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdAndSchoolId(any(), any(), any())).thenReturn(List.of());
    }

    private static SchoolClass schoolClass(Long id, String name) {
        SchoolClass c = new SchoolClass();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static Section section(Long id, Long classId, String name) {
        Section s = new Section();
        s.setId(id);
        s.setClassId(classId);
        s.setName(name);
        return s;
    }

    private static Teacher teacher(String id, TeacherStatus status) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setName("Name-" + id);
        t.setStatus(status);
        return t;
    }

    @Test
    void create_sectionlessClass_succeeds() {
        View view = service.create(new Request(SESSION_ID, CLASS_NO_SECTION_ID, null, "T1"), request);

        assertThat(view.className()).isEqualTo("10");
        assertThat(view.configuredTeacherId()).isEqualTo("T1");
        assertThat(view.sectionId()).isNull();
    }

    @Test
    void create_sectionSpecificClass_succeeds() {
        View view = service.create(new Request(SESSION_ID, CLASS_WITH_SECTION_ID, SECTION_ID, "T1"), request);

        assertThat(view.sectionId()).isEqualTo(SECTION_ID);
        assertThat(view.sectionName()).isEqualTo("A");
    }

    @Test
    void create_classWithSectionsButNoneSupplied_rejected() {
        assertThatThrownBy(() -> service.create(new Request(SESSION_ID, CLASS_WITH_SECTION_ID, null, "T1"), request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(responsibilityRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_sectionlessClassWithSectionSupplied_rejected() {
        assertThatThrownBy(() -> service.create(new Request(SESSION_ID, CLASS_NO_SECTION_ID, SECTION_ID, "T1"), request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_leftTeacher_rejected() {
        assertThatThrownBy(() -> service.create(new Request(SESSION_ID, CLASS_NO_SECTION_ID, null, "T-LEFT"), request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(responsibilityRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_unknownTeacher_rejected() {
        when(teacherRepository.findByTeacherIdAndSchoolId("GHOST", SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new Request(SESSION_ID, CLASS_NO_SECTION_ID, null, "GHOST"), request))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void create_historicalSession_rejected() {
        when(sessionAccess.lockWritableOwnedSession(SCHOOL_ID, SESSION_ID))
                .thenThrow(new IllegalStateException("Session has ended and is read-only."));

        assertThatThrownBy(() -> service.create(new Request(SESSION_ID, CLASS_NO_SECTION_ID, null, "T1"), request))
                .isInstanceOf(IllegalStateException.class);
        verify(responsibilityRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_dbUniquenessViolation_surfacedAsConflict() {
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(responsibilityRepository).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(new Request(SESSION_ID, CLASS_NO_SECTION_ID, null, "T1"), request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_sessionMismatch_rejectedClosed() {
        ClassTeacherResponsibility existing = new ClassTeacherResponsibility();
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(999L);
        when(responsibilityRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(5L, new Request(SESSION_ID, CLASS_NO_SECTION_ID, null, "T1"), request))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(sessionAccess, never()).lockWritableOwnedSession(any(), any());
    }

    @Test
    void update_ownSchoolAndSession_succeeds() {
        ClassTeacherResponsibility existing = new ClassTeacherResponsibility();
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(SESSION_ID);
        existing.setClassId(CLASS_NO_SECTION_ID);
        existing.setTeacherId("T1");
        when(responsibilityRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        View view = service.update(5L, new Request(SESSION_ID, CLASS_NO_SECTION_ID, null, "T1"), request);

        assertThat(view.id()).isEqualTo(5L);
    }

    @Test
    void delete_sessionMismatch_rejectedClosed() {
        ClassTeacherResponsibility existing = new ClassTeacherResponsibility();
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(999L);
        when(responsibilityRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(5L, SESSION_ID, request))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(responsibilityRepository, never()).deleteById(any());
    }

    @Test
    void delete_ownSchoolAndSession_succeeds() {
        ClassTeacherResponsibility existing = new ClassTeacherResponsibility();
        existing.setId(5L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(SESSION_ID);
        when(responsibilityRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        service.delete(5L, SESSION_ID, request);

        verify(responsibilityRepository).deleteById(5L);
    }

    @Test
    void list_showsLiveComparison_matchAndMismatch() {
        ClassTeacherResponsibility row = new ClassTeacherResponsibility();
        row.setId(1L);
        row.setSchoolId(SCHOOL_ID);
        row.setAcademicSessionId(SESSION_ID);
        row.setClassId(CLASS_NO_SECTION_ID);
        row.setTeacherId("T1");
        when(responsibilityRepository.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of(row));
        // Live projection currently says a DIFFERENT teacher holds class "10" (not yet activated).
        Teacher liveHolder = teacher("T-OTHER", TeacherStatus.ACTIVE);
        when(teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId("10", SCHOOL_ID))
                .thenReturn(List.of(liveHolder));

        List<View> views = service.list(SESSION_ID);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).configuredTeacherId()).isEqualTo("T1");
        assertThat(views.get(0).liveTeacherId()).isEqualTo("T-OTHER");
        assertThat(views.get(0).liveMatchesConfigured()).isFalse();
    }
}
