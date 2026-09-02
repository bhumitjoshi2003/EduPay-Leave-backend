package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.notification.NotificationAudience;
import com.indraacademy.ias_management.notification.NotificationAudienceType;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class NotificationRecipientResolverTest {
    @Mock StudentRepository studentRepository;
    @Mock TeacherRepository teacherRepository;
    @Mock UserRepository userRepository;
    @Mock ParentPortalService parentPortalService;

    private NotificationRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new NotificationRecipientResolver(
                studentRepository, teacherRepository, userRepository, parentPortalService);
        lenient().when(userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(eq(2L), any(Collection.class)))
                .thenAnswer(invocation -> ((Collection<String>) invocation.getArgument(1)).stream()
                        .map(id -> user(id, "STUDENT", 2L)).toList());
    }

    @Test
    void classAudienceSnapshotsActiveStudentsAndOnlyCanonicalActiveParents() {
        when(studentRepository.findByClassNameAndStatusAndSchoolId("9", StudentStatus.ACTIVE, 2L))
                .thenReturn(List.of(student("student-1", StudentStatus.ACTIVE)));
        when(parentPortalService.findActiveParentIdsForStudents(eq(2L), eq(List.of("student-1")), any(LocalDate.class)))
                .thenReturn(List.of("parent-active"));

        var recipients = resolver.resolve(2L, new NotificationAudience(NotificationAudienceType.CLASS, "9"));

        assertThat(recipients).containsExactly("student-1", "parent-active");
        assertThat(recipients).doesNotContain("parent-inactive", "parent-unlinked");
    }

    @Test
    void parentAudienceUsesAuthoritativeEffectiveRelationshipResolver() {
        when(studentRepository.findByStatusAndSchoolId(StudentStatus.ACTIVE, 2L))
                .thenReturn(List.of(student("student-1", StudentStatus.ACTIVE)));
        when(parentPortalService.findActiveParentIdsForStudents(eq(2L), eq(List.of("student-1")), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThat(resolver.resolve(2L, new NotificationAudience(NotificationAudienceType.PARENTS, null)))
                .isEmpty();
    }

    @Test
    void broadAudienceNeverIncludesExitedStudents() {
        when(studentRepository.findByStatusAndSchoolId(StudentStatus.ACTIVE, 2L))
                .thenReturn(List.of(student("active-1", StudentStatus.ACTIVE)));
        when(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, 2L)).thenReturn(List.of());
        when(parentPortalService.findActiveParentIdsForStudents(eq(2L), eq(List.of("active-1")), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThat(resolver.resolve(2L, new NotificationAudience(NotificationAudienceType.WHOLE_SCHOOL, null)))
                .containsExactly("active-1")
                .doesNotContain("withdrawn-1", "graduated-1");
    }

    @Test
    void directExitedStudentIsRejectedEvenWithActiveUserAccount() {
        User user = user("student-1", "STUDENT", 2L);
        when(userRepository.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user));
        when(studentRepository.findByStudentIdAndSchoolId("student-1", 2L))
                .thenReturn(Optional.of(student("student-1", StudentStatus.WITHDRAWN)));

        assertThatThrownBy(() -> resolver.resolve(2L, NotificationAudience.directUser("student-1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Exited or inactive");
    }

    @Test
    void directCrossSchoolUserIsRejected() {
        when(userRepository.findByUserIdAndSchoolIdAndActiveTrue("school-3-user", 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(2L, NotificationAudience.directUser("school-3-user")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target school");
    }

    @Test
    void teacherAudienceIncludesOnlyActiveTeacherAccountsInTenant() {
        Teacher teacher = new Teacher();
        teacher.setTeacherId("teacher-1");
        teacher.setSchoolId(2L);
        teacher.setStatus(TeacherStatus.ACTIVE);
        when(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, 2L)).thenReturn(List.of(teacher));

        assertThat(resolver.resolve(2L, new NotificationAudience(NotificationAudienceType.TEACHERS, null)))
                .containsExactly("teacher-1");
    }

    private Student student(String id, StudentStatus status) {
        Student student = new Student();
        student.setStudentId(id);
        student.setSchoolId(2L);
        student.setStatus(status);
        student.setClassName("9");
        return student;
    }

    private User user(String id, String role, Long schoolId) {
        User user = new User();
        user.setUserId(id);
        user.setRole(role);
        user.setSchoolId(schoolId);
        user.setActive(true);
        return user;
    }
}
