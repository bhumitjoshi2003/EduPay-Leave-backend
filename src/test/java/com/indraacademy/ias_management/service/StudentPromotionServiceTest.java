package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getSchoolClassSequence() used to fall back to a hardcoded default Indian class sequence
 * ("Play Group", "Nursery", ..., "1".."12") whenever a school had zero SchoolClass rows —
 * meaning promotion could save a student against a class name with no matching SchoolClass,
 * via a direct studentRepository.save() that bypassed addStudent/updateStudent's validation
 * entirely. These tests confirm that fallback is gone and promotion now fails safely instead.
 */
@ExtendWith(MockitoExtension.class)
class StudentPromotionServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private StudentPromotionService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new StudentPromotionService();
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(sectionRepository.findBySchoolIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, true))
                .thenReturn(List.of());
    }

    private PromotionDecisionRequest promoteRequest(String studentId) {
        PromotionDecisionRequest.Decision decision = new PromotionDecisionRequest.Decision();
        decision.setStudentId(studentId);
        decision.setAction("PROMOTE");
        PromotionDecisionRequest req = new PromotionDecisionRequest();
        req.setDecisions(List.of(decision));
        return req;
    }

    @Test
    void promotionFailsSafelyWhenNoClassesAreConfiguredForTheSchool() {
        // No fallback sequence — a school with zero SchoolClass rows has nothing to promote into.
        when(schoolClassRepository.findBySchoolIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, true))
                .thenReturn(List.of());

        Student student = new Student();
        student.setStudentId("S1");
        student.setSchoolId(SCHOOL_ID);
        student.setClassName("10");
        student.setStatus(StudentStatus.ACTIVE);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));

        PromotionResultDTO result = service.executePromotion(promoteRequest("S1"), request);

        assertThat(result.getPromoted()).isEqualTo(0);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getReason()).contains("no classes are configured");
        verify(studentRepository, never()).save(any());
    }

    @Test
    void promotionSucceedsIntoARealConfiguredNextClass() {
        SchoolClass cls9 = new SchoolClass();
        cls9.setId(9L); cls9.setSchoolId(SCHOOL_ID); cls9.setName("9");
        cls9.setActive(true); cls9.setDisplayOrder(1);
        SchoolClass cls10 = new SchoolClass();
        cls10.setId(10L); cls10.setSchoolId(SCHOOL_ID); cls10.setName("10");
        cls10.setActive(true); cls10.setDisplayOrder(2);
        when(schoolClassRepository.findBySchoolIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, true))
                .thenReturn(List.of(cls9, cls10));

        Student student = new Student();
        student.setStudentId("S1");
        student.setSchoolId(SCHOOL_ID);
        student.setClassName("9");
        student.setStatus(StudentStatus.ACTIVE);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        PromotionResultDTO result = service.executePromotion(promoteRequest("S1"), request);

        assertThat(result.getPromoted()).isEqualTo(1);
        assertThat(result.getErrors()).isEmpty();
        assertThat(student.getClassName()).isEqualTo("10");
        assertThat(student.getClassId()).isEqualTo(10L);
    }
}
