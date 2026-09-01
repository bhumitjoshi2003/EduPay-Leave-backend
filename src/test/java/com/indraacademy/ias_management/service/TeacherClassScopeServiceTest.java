package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import com.indraacademy.ias_management.service.TeacherClassScopeService.TeacherScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Exhaustive coverage of the shared class+section authorization primitive used identically by
 * Attendance, Mark, Report Card, Leave, and Student controllers. This is the security-critical
 * scenario throughout: Class 12 has two sections, Science and Commerce, each with its own
 * class-teacher — Teacher A (Science) must never be able to see/act on Commerce students, and
 * vice versa, regardless of what a caller sends on the wire.
 */
@ExtendWith(MockitoExtension.class)
class TeacherClassScopeServiceTest {

    @Mock private TeacherRepository teacherRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;

    private TeacherClassScopeService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long CLASS_12_ID = 12L;
    private static final Long SCIENCE_ID = 100L;
    private static final Long COMMERCE_ID = 200L;

    @BeforeEach
    void setUp() {
        service = new TeacherClassScopeService();
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
    }

    private SchoolClass class12() {
        SchoolClass c = new SchoolClass();
        c.setId(CLASS_12_ID);
        c.setName("12");
        return c;
    }

    private Section section(Long id, String name) {
        Section s = new Section();
        s.setId(id);
        s.setName(name);
        s.setActive(true);
        return s;
    }

    private Teacher teacher(String id, String classTeacher, Long sectionId) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setClassTeacher(classTeacher);
        t.setClassTeacherSectionId(sectionId);
        return t;
    }

    /** Class 12 has Science + Commerce configured. */
    private void givenClass12HasScienceAndCommerce() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(class12()));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(List.of(section(SCIENCE_ID, "Science"), section(COMMERCE_ID, "Commerce")));
    }

    /** Class 10 has no sections configured. */
    private void givenClass10HasNoSections() {
        SchoolClass c10 = new SchoolClass();
        c10.setId(10L);
        c10.setName("10");
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(c10));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, 10L, true))
                .thenReturn(List.of());
    }

    // ── resolveOwnScope ─────────────────────────────────────────────────────

    @Test
    void teacherWithNoClassResponsibility_resolvesToEmptyScope() {
        when(teacherRepository.findByTeacherIdAndSchoolId("T0", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T0", null, null)));

        TeacherScope scope = service.resolveOwnScope("T0", SCHOOL_ID);

        assertThat(scope.hasClassResponsibility()).isFalse();
        assertThat(scope.sectionRequiredButMissing()).isFalse();
    }

    @Test
    void teacherA_scienceSection_resolvesCorrectly() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));

        TeacherScope scope = service.resolveOwnScope("TeacherA", SCHOOL_ID);

        assertThat(scope.className()).isEqualTo("12");
        assertThat(scope.sectionId()).isEqualTo(SCIENCE_ID);
        assertThat(scope.sectionRequiredButMissing()).isFalse();
    }

    @Test
    void teacherB_commerceSection_resolvesCorrectly() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherB", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherB", "12", COMMERCE_ID)));

        TeacherScope scope = service.resolveOwnScope("TeacherB", SCHOOL_ID);

        assertThat(scope.sectionId()).isEqualTo(COMMERCE_ID);
    }

    @Test
    void legacyAmbiguousAssignment_classHasSectionsButNoneSet_blocked() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("Legacy", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("Legacy", "12", null)));

        TeacherScope scope = service.resolveOwnScope("Legacy", SCHOOL_ID);

        assertThat(scope.sectionRequiredButMissing()).isTrue();
        assertThat(scope.sectionId()).isNull();
    }

    @Test
    void classWithNoSections_classOnlyScope_preservesOriginalBehavior() {
        givenClass10HasNoSections();
        when(teacherRepository.findByTeacherIdAndSchoolId("T10", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T10", "10", null)));

        TeacherScope scope = service.resolveOwnScope("T10", SCHOOL_ID);

        assertThat(scope.className()).isEqualTo("10");
        assertThat(scope.sectionId()).isNull();
        assertThat(scope.sectionRequiredButMissing()).isFalse();
    }

    // ── authorizeAndScopeToClass ────────────────────────────────────────────

    @Test
    void teacherA_requestsOwnClass_allowedAndScopedToOwnSection_ignoringClientSectionId() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));

        // Client tries to widen/redirect scope by sending Commerce's id — must be ignored.
        ScopedAccess access = service.authorizeAndScopeToClass(Role.TEACHER, "TeacherA", SCHOOL_ID, "12", COMMERCE_ID);

        assertThat(access.allowed()).isTrue();
        assertThat(access.effectiveSectionId()).isEqualTo(SCIENCE_ID);
    }

    @Test
    void teacherA_requestsOwnClass_omittingSectionId_stillScopedToOwnSection_notBroadened() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));

        ScopedAccess access = service.authorizeAndScopeToClass(Role.TEACHER, "TeacherA", SCHOOL_ID, "12", null);

        assertThat(access.allowed()).isTrue();
        assertThat(access.effectiveSectionId()).isEqualTo(SCIENCE_ID);
    }

    @Test
    void teacherA_requestsDifferentClass_rejected() {
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));

        ScopedAccess access = service.authorizeAndScopeToClass(Role.TEACHER, "TeacherA", SCHOOL_ID, "11", null);

        assertThat(access.allowed()).isFalse();
    }

    @Test
    void legacyAmbiguousTeacher_blockedFromCombinedClassAccess_withActionableMessage() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("Legacy", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("Legacy", "12", null)));

        ScopedAccess access = service.authorizeAndScopeToClass(Role.TEACHER, "Legacy", SCHOOL_ID, "12", null);

        assertThat(access.allowed()).isFalse();
        assertThat(access.errorMessage()).isEqualTo(TeacherClassScopeService.SECTION_REQUIRED_MESSAGE);
    }

    @Test
    void classWithNoSections_teacherAllowed_effectiveSectionIdNull() {
        givenClass10HasNoSections();
        when(teacherRepository.findByTeacherIdAndSchoolId("T10", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T10", "10", null)));

        ScopedAccess access = service.authorizeAndScopeToClass(Role.TEACHER, "T10", SCHOOL_ID, "10", null);

        assertThat(access.allowed()).isTrue();
        assertThat(access.effectiveSectionId()).isNull();
    }

    @Test
    void admin_alwaysAllowed_clientSectionIdPassedThroughUnchanged() {
        ScopedAccess access = service.authorizeAndScopeToClass(Role.ADMIN, "adminUser", SCHOOL_ID, "12", COMMERCE_ID);

        assertThat(access.allowed()).isTrue();
        assertThat(access.effectiveSectionId()).isEqualTo(COMMERCE_ID);
        // Admin path never even needs to look up the teacher.
    }

    // ── authorizeAndScopeToStudent ──────────────────────────────────────────

    @Test
    void teacherA_ownScienceStudent_allowed() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));

        ScopedAccess access = service.authorizeAndScopeToStudent(Role.TEACHER, "TeacherA", SCHOOL_ID, "12", SCIENCE_ID);

        assertThat(access.allowed()).isTrue();
    }

    @Test
    void teacherA_cannotAccessCommerceStudent_evenSameClass() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));

        ScopedAccess access = service.authorizeAndScopeToStudent(Role.TEACHER, "TeacherA", SCHOOL_ID, "12", COMMERCE_ID);

        assertThat(access.allowed()).isFalse();
        assertThat(access.errorMessage()).contains("section");
    }

    @Test
    void teacherB_cannotAccessScienceStudent() {
        givenClass12HasScienceAndCommerce();
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherB", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherB", "12", COMMERCE_ID)));

        ScopedAccess access = service.authorizeAndScopeToStudent(Role.TEACHER, "TeacherB", SCHOOL_ID, "12", SCIENCE_ID);

        assertThat(access.allowed()).isFalse();
    }

    @Test
    void teacherA_cannotAccessStudentInDifferentClassEntirely() {
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("TeacherA", "12", SCIENCE_ID)));

        ScopedAccess access = service.authorizeAndScopeToStudent(Role.TEACHER, "TeacherA", SCHOOL_ID, "11", null);

        assertThat(access.allowed()).isFalse();
    }

    @Test
    void admin_alwaysAllowedForAnyStudent() {
        ScopedAccess access = service.authorizeAndScopeToStudent(Role.ADMIN, "adminUser", SCHOOL_ID, "12", COMMERCE_ID);

        assertThat(access.allowed()).isTrue();
    }

    // ── Cross-tenant / cross-class / inactive section rejection (write-side rules also apply
    //    to resolution — these prove the LOOKUP itself is schoolId-scoped, matching what
    //    TeacherService.validateAndNormalizeClassResponsibility separately enforces on write) ──

    @Test
    void sectionLookupIsScopedToSchoolId_crossTenantSectionNeverConsidered() {
        // Section 999 exists, but under a DIFFERENT school's class — our schoolId-scoped query
        // simply never returns it, so classHasSections() for our school correctly sees none.
        SchoolClass c = class12();
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(c));
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(List.of()); // nothing for THIS school, even though "12" might have sections elsewhere

        boolean hasSections = service.classHasSections("12", SCHOOL_ID);

        assertThat(hasSections).isFalse();
    }

    @Test
    void unknownClass_classHasSections_returnsFalseRatherThanThrowing() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "99")).thenReturn(Optional.empty());

        assertThat(service.classHasSections("99", SCHOOL_ID)).isFalse();
    }

    @Test
    void teacherRecordMissingEntirely_resolvesToEmptyScope_notNullPointer() {
        when(teacherRepository.findByTeacherIdAndSchoolId("Ghost", SCHOOL_ID)).thenReturn(Optional.empty());

        TeacherScope scope = service.resolveOwnScope("Ghost", SCHOOL_ID);

        assertThat(scope.hasClassResponsibility()).isFalse();
    }
}
