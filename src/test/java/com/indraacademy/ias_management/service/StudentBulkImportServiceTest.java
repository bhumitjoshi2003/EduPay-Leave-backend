package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Date of birth is the sole source of a bulk-imported student's initial login
 * password (yyyyMMdd) — these tests lock in that a row missing it is rejected
 * with a clear, row-level error (not silently defaulted to the studentId, the
 * old fallback), while the partial-success import model is preserved: other
 * valid rows in the same file still succeed.
 *
 * <p>studentService.addStudent is mocked here (this is a unit test of the CSV parsing/
 * orchestration layer, not of ID generation itself — see IdGeneratorServiceTest and
 * StudentServiceTest for that) but its stub simulates the real method's actual behavior:
 * assigning a generated studentId to whatever Student it receives, since that assignment
 * now happens inside StudentService.addStudent(), not in this parser.
 */
@ExtendWith(MockitoExtension.class)
class StudentBulkImportServiceTest {

    @Mock private StudentService studentService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private WelcomeEmailService welcomeEmailService;
    @Mock private HttpServletRequest request;

    private StudentBulkImportService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new StudentBulkImportService();
        ReflectionTestUtils.setField(service, "studentService", studentService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "welcomeEmailService", welcomeEmailService);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(schoolClassRepository.findBySchoolIdAndName(any(), anyString())).thenReturn(Optional.empty());
        AtomicInteger seq = new AtomicInteger(1);
        lenient().when(studentService.addStudent(any(Student.class), any())).thenAnswer(inv -> {
            Student s = inv.getArgument(0);
            s.setStudentId(String.format("stu_260100%02d", seq.getAndIncrement()));
            return s;
        });
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");
    }

    /** New-format CSV — no ID column at all, matching the current (post-generation)
     *  TEMPLATE_HEADERS and the header-name-based parser. */
    private MockMultipartFile csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(String.join(",", StudentBulkImportService.TEMPLATE_HEADERS)).append("\n");
        for (String row : dataRows) {
            sb.append(row).append("\n");
        }
        return new MockMultipartFile("file", "students.csv", "text/csv",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rowMissingDobIsRejectedWithClearMessage_otherRowsStillSucceed() {
        MockMultipartFile file = csv(
                "Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,",
                "No Dob Student,s2@test.com,,,10,,,,,,,2024-01-01,"
        );

        BulkImportResultDTO result = service.bulkImport(file, request);

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSuccessful()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getReason())
                .isEqualTo("Date of birth is required because it is used as the initial password.");
        assertThat(result.getErrors().get(0).getStudentId()).isEqualTo("No Dob Student");
    }

    @Test
    void validRowCreatesUserWithDobDerivedPasswordAndMustChangePasswordTrue() {
        MockMultipartFile file = csv("Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,");

        service.bulkImport(file, request);

        verify(passwordEncoder).encode("19900523");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getUserId()).startsWith("stu_26");
        assertThat(saved.getRole()).isEqualTo(Role.STUDENT);
        assertThat(saved.isMustChangePassword()).isTrue();
        assertThat(saved.getPassword()).isEqualTo("ENCODED");
    }

    @Test
    void validRowTriggersWelcomeEmailAfterUserAccountIsCreated() {
        MockMultipartFile file = csv("Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,");

        service.bulkImport(file, request);

        ArgumentCaptor<String> studentIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(welcomeEmailService).sendWelcomeEmail(studentIdCaptor.capture(), org.mockito.Mockito.eq("Valid Student"),
                org.mockito.Mockito.eq(Role.STUDENT), org.mockito.Mockito.eq("s1@test.com"), org.mockito.Mockito.eq(SCHOOL_ID));
        assertThat(studentIdCaptor.getValue()).startsWith("stu_26");
    }

    @Test
    void rowMissingDobDoesNotTriggerWelcomeEmail() {
        MockMultipartFile file = csv("No Dob Student,s2@test.com,,,10,,,,,,,2024-01-01,");

        service.bulkImport(file, request);

        verify(welcomeEmailService, org.mockito.Mockito.never())
                .sendWelcomeEmail(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void generatedStudentIdIsReportedInSuccessfulImportResult() {
        MockMultipartFile file = csv("Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,");

        BulkImportResultDTO result = service.bulkImport(file, request);

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getName()).isEqualTo("Valid Student");
        assertThat(result.getCreated().get(0).getGeneratedId()).startsWith("stu_26");
        assertThat(result.getNotice()).isNull();
    }

    @Test
    void legacyCsvWithStudentIdColumnIsAcceptedButIgnored_noticeExplainsWhy() {
        // An old CSV still has "Student ID" as the first column. It must not error, and the
        // value in that column must never become the generated account's ID.
        MockMultipartFile file = new MockMultipartFile("file", "students.csv", "text/csv",
                ("Student ID,Student Name,Email,Phone Number,Date of Birth,Class,Section,Gender,Father Name,Mother Name,Takes Bus,Distance (km),Joining Date,Leaving Date\n"
                        + "LEGACY_ID_123,Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,\n")
                        .getBytes(StandardCharsets.UTF_8));

        BulkImportResultDTO result = service.bulkImport(file, request);

        assertThat(result.getSuccessful()).isEqualTo(1);
        assertThat(result.getCreated().get(0).getGeneratedId()).isNotEqualTo("LEGACY_ID_123");
        assertThat(result.getCreated().get(0).getGeneratedId()).startsWith("stu_26");
        assertThat(result.getNotice()).contains("Student ID").contains("generates the account ID automatically");
    }
}
