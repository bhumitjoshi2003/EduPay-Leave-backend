package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ParentDtos;
import com.indraacademy.ias_management.entity.Parent;
import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.ParentRepository;
import com.indraacademy.ias_management.repository.ParentStudentRelationshipRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParentPortalServiceTest {
    private static final Long SCHOOL_ID = 2L;
    private static final String PARENT_ID = "PAR_1001";

    @Mock private ParentRepository parentRepository;
    @Mock private ParentStudentRelationshipRepository relationshipRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SecurityUtil securityUtil;
    @Mock private EntitlementService entitlementService;
    @Mock private IdGeneratorService idGeneratorService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private SchoolRepository schoolRepository;

    private ParentPortalService service;

    @BeforeEach
    void setUp() {
        service = new ParentPortalService(parentRepository, relationshipRepository, studentRepository,
                userRepository, passwordEncoder, securityUtil, entitlementService,
                idGeneratorService, passwordResetService, schoolRepository);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    @Test
    void getParent_excludesInactiveHistoricalRelationshipsFromLinkedChildrenAndCount() {
        Parent parent = parent();
        ParentStudentRelationship active = relationship(1L, "STU_001", true);
        ParentStudentRelationship unlinkedHistory = relationship(2L, "STU_002", false);
        Student student = new Student();
        student.setStudentId("STU_001");
        student.setName("Active Student");
        student.setClassName("9");
        student.setSectionName("A");

        when(parentRepository.findByParentIdAndSchoolId(PARENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(parent));
        when(relationshipRepository
                .findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(SCHOOL_ID, PARENT_ID))
                .thenReturn(List.of(active, unlinkedHistory));
        when(studentRepository.findByStudentIdAndSchoolId("STU_001", SCHOOL_ID))
                .thenReturn(Optional.of(student));

        ParentDtos.ParentProfile profile = service.getParent(PARENT_ID);

        assertThat(profile.parent().linkedChildren()).isEqualTo(1);
        assertThat(profile.children()).singleElement()
                .extracting(ParentDtos.ChildAccess::studentId)
                .isEqualTo("STU_001");
        verify(entitlementService).requireFeature(SCHOOL_ID, "PARENT_PORTAL");
    }

    @Test
    void getParent_withOnlyUnlinkedHistoryReturnsNoCurrentChildren() {
        Parent parent = parent();
        ParentStudentRelationship unlinkedHistory = relationship(2L, "STU_002", false);

        when(parentRepository.findByParentIdAndSchoolId(PARENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(parent));
        when(relationshipRepository
                .findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(SCHOOL_ID, PARENT_ID))
                .thenReturn(List.of(unlinkedHistory));

        ParentDtos.ParentProfile profile = service.getParent(PARENT_ID);

        assertThat(profile.parent().linkedChildren()).isZero();
        assertThat(profile.children()).isEmpty();
    }

    @Test
    void getParent_excludesAnExitedStudentEvenThoughTheRelationshipIsStillMarkedActive() {
        // relationship.active == true here on purpose: unlinking is a separate, deliberate admin
        // action from a student exiting, so a graduated/transferred/withdrawn student can easily
        // still have active=true on their old relationship row. getParent() (activeOnly=false,
        // the admin parent-detail view) must not rely on relationship.active alone — it has to
        // independently check the linked student's own exit status, same as myProfile() does.
        Parent parent = parent();
        ParentStudentRelationship stillActiveLink = relationship(1L, "STU_003", true);
        Student exited = new Student();
        exited.setStudentId("STU_003");
        exited.setName("Graduated Student");
        exited.setClassName("12");
        exited.setStatus(StudentStatus.GRADUATED);

        when(parentRepository.findByParentIdAndSchoolId(PARENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(parent));
        when(relationshipRepository
                .findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(SCHOOL_ID, PARENT_ID))
                .thenReturn(List.of(stillActiveLink));
        when(studentRepository.findByStudentIdAndSchoolId("STU_003", SCHOOL_ID))
                .thenReturn(Optional.of(exited));

        ParentDtos.ParentProfile profile = service.getParent(PARENT_ID);

        assertThat(profile.children()).isEmpty();
        assertThat(profile.parent().linkedChildren()).isZero();
    }

    @Test
    void notificationRecipientResolverIncludesOnlyActiveEffectiveParents() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        Student student = activeStudent("STU_001");
        ParentStudentRelationship active = relationship(1L, "STU_001", true);
        active.setEffectiveFrom(today.minusDays(1));
        ParentStudentRelationship inactive = relationship(2L, "STU_001", false);
        inactive.setParentId("PAR_INACTIVE_LINK");
        Parent inactiveParent = parent();
        inactiveParent.setParentId("PAR_DISABLED");
        inactiveParent.setActive(false);
        ParentStudentRelationship disabledParentLink = relationship(3L, "STU_001", true);
        disabledParentLink.setParentId("PAR_DISABLED");

        when(studentRepository.findByStudentIdAndSchoolId("STU_001", SCHOOL_ID))
                .thenReturn(Optional.of(student));
        when(relationshipRepository.findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(SCHOOL_ID, "STU_001"))
                .thenReturn(List.of(active, inactive, disabledParentLink));
        when(parentRepository.findByParentIdAndSchoolId(PARENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(parent()));
        User activeParentUser = new User();
        activeParentUser.setUserId(PARENT_ID);
        activeParentUser.setSchoolId(SCHOOL_ID);
        activeParentUser.setActive(true);
        when(userRepository.findByUserIdAndSchoolIdAndActiveTrue(PARENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(activeParentUser));
        when(parentRepository.findByParentIdAndSchoolId("PAR_DISABLED", SCHOOL_ID))
                .thenReturn(Optional.of(inactiveParent));

        assertThat(service.findActiveParentIdsForStudents(SCHOOL_ID, List.of("STU_001"), today))
                .containsExactly(PARENT_ID);
    }

    @Test
    void notificationRecipientResolverExcludesParentWithDisabledLogin() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        ParentStudentRelationship active = relationship(1L, "STU_001", true);
        active.setEffectiveFrom(today.minusDays(1));

        when(studentRepository.findByStudentIdAndSchoolId("STU_001", SCHOOL_ID))
                .thenReturn(Optional.of(activeStudent("STU_001")));
        when(relationshipRepository.findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(SCHOOL_ID, "STU_001"))
                .thenReturn(List.of(active));
        when(parentRepository.findByParentIdAndSchoolId(PARENT_ID, SCHOOL_ID))
                .thenReturn(Optional.of(parent()));
        when(userRepository.findByUserIdAndSchoolIdAndActiveTrue(PARENT_ID, SCHOOL_ID))
                .thenReturn(Optional.empty());

        assertThat(service.findActiveParentIdsForStudents(SCHOOL_ID, List.of("STU_001"), today))
                .isEmpty();
    }

    @Test
    void notificationRecipientResolverExcludesFutureAndExpiredRelationships() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        ParentStudentRelationship future = relationship(1L, "STU_001", true);
        future.setEffectiveFrom(today.plusDays(1));
        ParentStudentRelationship expired = relationship(2L, "STU_001", true);
        expired.setEffectiveFrom(today.minusMonths(1));
        expired.setEffectiveUntil(today.minusDays(1));

        when(studentRepository.findByStudentIdAndSchoolId("STU_001", SCHOOL_ID))
                .thenReturn(Optional.of(activeStudent("STU_001")));
        when(relationshipRepository.findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(SCHOOL_ID, "STU_001"))
                .thenReturn(List.of(future, expired));

        assertThat(service.findActiveParentIdsForStudents(SCHOOL_ID, List.of("STU_001"), today))
                .isEmpty();
        verify(parentRepository, never()).findByParentIdAndSchoolId(anyString(), any());
    }

    @Test
    void notificationRecipientResolverExcludesExitedAndCrossSchoolStudents() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        Student withdrawn = activeStudent("STU_EXITED");
        withdrawn.setStatus(StudentStatus.WITHDRAWN);
        when(studentRepository.findByStudentIdAndSchoolId("STU_EXITED", SCHOOL_ID))
                .thenReturn(Optional.of(withdrawn));
        when(studentRepository.findByStudentIdAndSchoolId("STU_OTHER_SCHOOL", SCHOOL_ID))
                .thenReturn(Optional.empty());

        assertThat(service.findActiveParentIdsForStudents(
                SCHOOL_ID, List.of("STU_EXITED", "STU_OTHER_SCHOOL"), today)).isEmpty();
        verify(relationshipRepository, never())
                .findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(any(), anyString());
    }

    // ─── createParent (Option A onboarding) ────────────────────────────────

    private ParentDtos.CreateParentRequest createRequest() {
        return new ParentDtos.CreateParentRequest("Rajesh Joshi", "rajesh@example.com", "9876543210");
    }

    @Test
    void createParent_generatesParentId_neverAcceptsOneFromTheCaller() {
        when(idGeneratorService.generateParentId()).thenReturn("par_26010001");
        when(passwordEncoder.encode(anyString())).thenReturn("HASHED");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Indra Academy")));

        ParentDtos.ParentProfile profile = service.createParent(createRequest());

        assertThat(profile.parent().parentId()).isEqualTo("par_26010001");
        verify(idGeneratorService).generateParentId();
    }

    @Test
    void createParent_neverExposesAPlaintextPassword_toTheAdminOrTheStoredHash() {
        when(idGeneratorService.generateParentId()).thenReturn("par_26010001");
        when(passwordEncoder.encode(anyString())).thenReturn("HASHED_PLACEHOLDER");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Indra Academy")));

        service.createParent(createRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getPassword()).isEqualTo("HASHED_PLACEHOLDER");
        assertThat(saved.isMustChangePassword()).isTrue();
        // The response DTO (ParentProfile/ParentSummary) has no password-shaped field at
        // all — nothing to assert isn't there beyond confirming the type has no such
        // accessor, which the compiler already enforces. The real assertion is behavioral:
        // the ONLY password communication path is the emailed link, verified next.
    }

    @Test
    void createParent_sendsTheAccountSetupLink_reusingTheExistingPasswordResetMechanism() {
        when(idGeneratorService.generateParentId()).thenReturn("par_26010001");
        when(passwordEncoder.encode(anyString())).thenReturn("HASHED");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Indra Academy")));

        service.createParent(createRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(passwordResetService).sendParentWelcomeLink(userCaptor.capture(), org.mockito.ArgumentMatchers.eq("Rajesh Joshi"),
                org.mockito.ArgumentMatchers.eq("Indra Academy"));
        assertThat(userCaptor.getValue().getUserId()).isEqualTo("par_26010001");
    }

    @Test
    void createParent_rejectsDuplicatePhoneWithinSchool() {
        when(parentRepository.existsByPhoneNumberAndSchoolId("9876543210", SCHOOL_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.createParent(createRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("phone number already exists");

        verify(idGeneratorService, never()).generateParentId();
        verify(parentRepository, never()).save(any());
    }

    @Test
    void createParent_rejectsDuplicateEmailWithinSchool() {
        when(parentRepository.existsByEmailIgnoreCaseAndSchoolId("rajesh@example.com", SCHOOL_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.createParent(createRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("email already exists");

        verify(idGeneratorService, never()).generateParentId();
        verify(parentRepository, never()).save(any());
    }

    private School school(String name) {
        School school = new School();
        school.setName(name);
        return school;
    }

    private Parent parent() {
        Parent parent = new Parent();
        parent.setParentId(PARENT_ID);
        parent.setSchoolId(SCHOOL_ID);
        parent.setName("Test Parent");
        parent.setPhoneNumber("9999999999");
        parent.setActive(true);
        return parent;
    }

    private ParentStudentRelationship relationship(Long id, String studentId, boolean active) {
        ParentStudentRelationship relationship = new ParentStudentRelationship();
        relationship.setId(id);
        relationship.setSchoolId(SCHOOL_ID);
        relationship.setParentId(PARENT_ID);
        relationship.setStudentId(studentId);
        relationship.setRelationshipType("PARENT");
        relationship.setEffectiveFrom(LocalDate.now().minusDays(1));
        relationship.setActive(active);
        return relationship;
    }

    private Student activeStudent(String studentId) {
        Student student = new Student();
        student.setStudentId(studentId);
        student.setSchoolId(SCHOOL_ID);
        student.setStatus(StudentStatus.ACTIVE);
        return student;
    }
}
