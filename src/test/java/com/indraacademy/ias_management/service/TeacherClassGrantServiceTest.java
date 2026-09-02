package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherClassGrant;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherClassGrantRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherClassGrantServiceTest {

    @Mock private TeacherClassGrantRepository grantRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private TeacherClassGrantService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long CLASS_12_ID = 12L;
    private static final Long SCIENCE_ID = 501L;

    @BeforeEach
    void setUp() {
        service = new TeacherClassGrantService();
        ReflectionTestUtils.setField(service, "grantRepository", grantRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin1");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(new Teacher()));
        lenient().when(grantRepository.save(any(TeacherClassGrant.class))).thenAnswer(inv -> {
            TeacherClassGrant g = inv.getArgument(0);
            if (g.getId() == null) g.setId(100L);
            return g;
        });
    }

    private SchoolClass schoolClass(Long id, String name) {
        SchoolClass c = new SchoolClass();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private Section section(Long id, String name) {
        Section s = new Section();
        s.setId(id);
        s.setName(name);
        return s;
    }

    @Test
    void create_classWithSections_validSection_succeeds() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(schoolClass(CLASS_12_ID, "12")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(List.of(section(SCIENCE_ID, "Science")));
        when(grantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "12", SCIENCE_ID, SCHOOL_ID))
                .thenReturn(false);

        TeacherClassGrant saved = service.create("T1", "12", SCIENCE_ID, request);

        assertThat(saved.getSectionName()).isEqualTo("Science");
        assertThat(saved.getClassId()).isEqualTo(CLASS_12_ID);
        assertThat(saved.getGrantedBy()).isEqualTo("admin1");
        verify(grantRepository).save(any(TeacherClassGrant.class));
    }

    @Test
    void create_classWithSections_missingSection_rejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(schoolClass(CLASS_12_ID, "12")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(List.of(section(SCIENCE_ID, "Science")));

        assertThatThrownBy(() -> service.create("T1", "12", null, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(grantRepository, never()).save(any());
    }

    @Test
    void create_classWithoutSections_sectionSupplied_rejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "4")).thenReturn(Optional.of(schoolClass(4L, "4")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 4L, true))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create("T1", "4", 9L, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(grantRepository, never()).save(any());
    }

    @Test
    void create_classWithoutSections_noSection_succeeds() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "4")).thenReturn(Optional.of(schoolClass(4L, "4")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 4L, true))
                .thenReturn(List.of());
        when(grantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "4", null, SCHOOL_ID))
                .thenReturn(false);

        TeacherClassGrant saved = service.create("T1", "4", null, request);

        assertThat(saved.getSectionName()).isNull();
        verify(grantRepository).save(any());
    }

    @Test
    void create_unknownClass_rejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("T1", "99", null, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_sectionFromDifferentClass_rejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(schoolClass(CLASS_12_ID, "12")));
        // The active-sections list for class 12 doesn't include this section id (it belongs elsewhere)
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(List.of(section(SCIENCE_ID, "Science")));

        assertThatThrownBy(() -> service.create("T1", "12", 999L, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(grantRepository, never()).save(any());
    }

    @Test
    void create_unknownTeacher_rejected() {
        when(teacherRepository.findByTeacherIdAndSchoolId("T99", SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("T99", "12", null, request))
                .isInstanceOf(NoSuchElementException.class);

        verify(grantRepository, never()).save(any());
    }

    @Test
    void create_duplicateGrant_rejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "4")).thenReturn(Optional.of(schoolClass(4L, "4")));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 4L, true))
                .thenReturn(List.of());
        when(grantRepository.existsByTeacherIdAndClassNameAndSectionIdAndSchoolId("T1", "4", null, SCHOOL_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create("T1", "4", null, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(grantRepository, never()).save(any());
    }

    @Test
    void delete_ownSchoolGrant_succeeds() {
        TeacherClassGrant existing = new TeacherClassGrant();
        existing.setId(7L);
        existing.setSchoolId(SCHOOL_ID);
        when(grantRepository.findByIdAndSchoolId(7L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        service.delete(7L, request);

        verify(grantRepository).deleteById(7L);
    }

    @Test
    void delete_grantFromAnotherSchool_notFound() {
        when(grantRepository.findByIdAndSchoolId(7L, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(7L, request))
                .isInstanceOf(NoSuchElementException.class);

        verify(grantRepository, never()).deleteById(any());
    }

    @Test
    void getForTeacher_delegatesToRepository() {
        TeacherClassGrant g = new TeacherClassGrant();
        when(grantRepository.findByTeacherIdAndSchoolIdOrderByCreatedAtDesc("T1", SCHOOL_ID)).thenReturn(List.of(g));

        List<TeacherClassGrant> result = service.getForTeacher("T1");

        assertThat(result).containsExactly(g);
    }
}
