package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ParentDtos;
import com.indraacademy.ias_management.entity.Parent;
import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.ParentRepository;
import com.indraacademy.ias_management.repository.ParentStudentRelationshipRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    private ParentPortalService service;

    @BeforeEach
    void setUp() {
        service = new ParentPortalService(parentRepository, relationshipRepository, studentRepository,
                userRepository, passwordEncoder, securityUtil, entitlementService);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
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
}
