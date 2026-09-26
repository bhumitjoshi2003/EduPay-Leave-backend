package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.*;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import com.indraacademy.ias_management.service.TeacherClassScopeService.TeacherScope;
import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Results Phase 1 authorization: visibility by role, admin-only publishing, compute teacher scope. */
@ExtendWith(MockitoExtension.class)
class ResultsAuthorizationTest {

    @Mock private MarkService markService;
    @Mock private ExamConfigService examConfigService;
    @Mock private SecurityUtil securityUtil;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassScopeService teacherClassScopeService;
    @Mock private ParentPortalService parentPortalService;
    @Mock private WeightageCalculationEngine weightageEngine;
    @InjectMocks private MarkController markController;
    @InjectMocks private AssessmentGroupController assessmentGroupController;
    @InjectMocks private ExamController examController;

    @Test
    void studentsAndParentsOnlyReceivePublishedResults() {
        when(securityUtil.getRole()).thenReturn("STUDENT");
        when(securityUtil.getUsername()).thenReturn("S1");
        markController.getStudentResults("S1", "2026-2027");
        verify(markService).getStudentResults("S1", "2026-2027", false);

        when(securityUtil.getRole()).thenReturn("PARENT");
        markController.getStudentResults("S1", "2026-2027");
        verify(markService, times(2)).getStudentResults("S1", "2026-2027", false);

        when(securityUtil.getRole()).thenReturn("ADMIN");
        markController.getStudentResults("S1", "2026-2027");
        verify(markService).getStudentResults("S1", "2026-2027", true);
    }

    @Test
    void publishingIsAdminOnly() throws Exception {
        for (String method : List.of("publishResults", "unpublishResults")) {
            PreAuthorize rule = ExamController.class.getMethod(method, Long.class, jakarta.servlet.http.HttpServletRequest.class)
                    .getAnnotation(PreAuthorize.class);
            assertThat(rule.value()).isEqualTo("hasRole('ADMIN')");
        }
    }

    @Test
    void teacherCannotComputeAnotherClassStudentsResult() {
        when(securityUtil.getRole()).thenReturn("TEACHER");
        when(securityUtil.getUsername()).thenReturn("T1");
        when(securityUtil.getSchoolId()).thenReturn(4L);
        Student other = new Student();
        other.setClassName("9");
        other.setSectionId(20L);
        when(studentRepository.findByStudentIdAndSchoolId("S9", 4L)).thenReturn(Optional.of(other));
        when(teacherClassScopeService.authorizeAndScopeToStudent("TEACHER", "T1", 4L, "9", 20L))
                .thenReturn(ScopedAccess.deny("Not your student"));

        assertThat(assessmentGroupController.computeForStudent(1L, "S9", "2026-2027").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(weightageEngine);
    }

    // ── GET /api/exams: TEACHER is scoped to their own class; ADMIN stays school-wide ────────────

    private void asTeacher() {
        when(securityUtil.getRole()).thenReturn("TEACHER");
        when(securityUtil.getUsername()).thenReturn("T1");
        when(securityUtil.getSchoolId()).thenReturn(4L);
    }

    @Test
    void teacherListsOnlyTheirOwnClassExams() {
        asTeacher();
        List<ExamConfig> own = List.of(new ExamConfig());
        when(teacherClassScopeService.authorizeAndScopeToClass("TEACHER", "T1", 4L, "8", null)).thenReturn(ScopedAccess.allow(3L));
        when(examConfigService.getExams("2026-2027", "8")).thenReturn(own);

        var res = examController.getExams("2026-2027", "8");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(own);
    }

    @Test
    void teacherCannotListAnotherClassExams() {
        asTeacher();
        when(teacherClassScopeService.authorizeAndScopeToClass("TEACHER", "T1", 4L, "9", null)).thenReturn(ScopedAccess.deny("Not your class"));

        assertThat(examController.getExams("2026-2027", "9").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(examConfigService);
    }

    @Test
    void teacherWithoutClassFilterIsNarrowedToTheirOwnClassNeverSchoolWide() {
        asTeacher();
        when(teacherClassScopeService.resolveOwnScope("T1", 4L)).thenReturn(new TeacherScope("8", 3L, false));
        when(teacherClassScopeService.authorizeAndScopeToClass("TEACHER", "T1", 4L, "8", null)).thenReturn(ScopedAccess.allow(3L));

        examController.getExams("2026-2027", null);
        verify(examConfigService).getExams("2026-2027", "8");
        verify(examConfigService, never()).getExams(eq("2026-2027"), isNull());
    }

    @Test
    void teacherWithNoClassResponsibilityGetsNoExams() {
        asTeacher();
        when(teacherClassScopeService.resolveOwnScope("T1", 4L)).thenReturn(new TeacherScope(null, null, false));

        var res = examController.getExams(null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) res.getBody()).isEmpty();
        verifyNoInteractions(examConfigService);
    }

    @Test
    void teacherWithAmbiguousSectionAssignmentIsRefused() {
        asTeacher();
        when(teacherClassScopeService.authorizeAndScopeToClass("TEACHER", "T1", 4L, "8", null))
                .thenReturn(ScopedAccess.deny(TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));

        assertThat(examController.getExams("2026-2027", "8").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(examConfigService);
    }

    @Test
    void adminListsSchoolWideWithoutTeacherScope() {
        when(securityUtil.getRole()).thenReturn("ADMIN");
        examController.getExams("2026-2027", null);
        verify(examConfigService).getExams("2026-2027", null);
        verifyNoInteractions(teacherClassScopeService);
    }
}
