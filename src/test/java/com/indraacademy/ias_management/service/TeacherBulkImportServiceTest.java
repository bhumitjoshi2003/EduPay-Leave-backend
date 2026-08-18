package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors StudentBulkImportServiceTest: date of birth is the sole source of a
 * bulk-imported teacher's initial login password (yyyyMMdd) — a row missing it
 * must be rejected with a clear, row-level error, not silently defaulted to the
 * teacherId (the old fallback), while other valid rows in the same file still
 * succeed.
 */
@ExtendWith(MockitoExtension.class)
class TeacherBulkImportServiceTest {

    @Mock private TeacherService teacherService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private WelcomeEmailService welcomeEmailService;
    @Mock private HttpServletRequest request;

    private TeacherBulkImportService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new TeacherBulkImportService();
        ReflectionTestUtils.setField(service, "teacherService", teacherService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "welcomeEmailService", welcomeEmailService);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(teacherService.addTeacher(any(Teacher.class), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");
    }

    private MockMultipartFile csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(String.join(",", TeacherBulkImportService.TEMPLATE_HEADERS)).append("\n");
        for (String row : dataRows) {
            sb.append(row).append("\n");
        }
        return new MockMultipartFile("file", "teachers.csv", "text/csv",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rowMissingDobIsRejectedWithClearMessage_otherRowsStillSucceed() {
        // Columns: Teacher ID, Teacher Name, Email, Phone Number, Date of Birth, Gender, Class Teacher, Joining Date
        MockMultipartFile file = csv(
                "T1,Valid Teacher,t1@test.com,,1985-03-20,,,2024-01-01",
                "T2,No Dob Teacher,t2@test.com,,,,,2024-01-01"
        );

        BulkImportResultDTO result = service.bulkImport(file, request);

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSuccessful()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getReason())
                .isEqualTo("Date of birth is required because it is used as the initial password.");
        assertThat(result.getErrors().get(0).getStudentId()).isEqualTo("T2");
    }

    @Test
    void validRowCreatesUserWithDobDerivedPasswordAndMustChangePasswordTrue() {
        MockMultipartFile file = csv("T1,Valid Teacher,t1@test.com,,1985-03-20,,,2024-01-01");

        service.bulkImport(file, request);

        verify(passwordEncoder).encode("19850320");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo("T1");
        assertThat(saved.getRole()).isEqualTo(Role.TEACHER);
        assertThat(saved.isMustChangePassword()).isTrue();
        assertThat(saved.getPassword()).isEqualTo("ENCODED");
    }

    @Test
    void validRowTriggersWelcomeEmailAfterUserAccountIsCreated() {
        MockMultipartFile file = csv("T1,Valid Teacher,t1@test.com,,1985-03-20,,,2024-01-01");

        service.bulkImport(file, request);

        verify(welcomeEmailService).sendWelcomeEmail("T1", "Valid Teacher", Role.TEACHER, "t1@test.com", SCHOOL_ID);
    }

    @Test
    void rowMissingDobDoesNotTriggerWelcomeEmail() {
        MockMultipartFile file = csv("T2,No Dob Teacher,t2@test.com,,,,,2024-01-01");

        service.bulkImport(file, request);

        verify(welcomeEmailService, org.mockito.Mockito.never())
                .sendWelcomeEmail(anyString(), anyString(), anyString(), anyString(), any());
    }
}
