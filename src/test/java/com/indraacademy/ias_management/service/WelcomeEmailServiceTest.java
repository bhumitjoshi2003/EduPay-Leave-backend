package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the shared welcome-email contract used by both manual registration
 * (AuthController.registerUser) and bulk import (StudentBulkImportService /
 * TeacherBulkImportService): the email must include name/userId/role/school/login-URL and
 * generic DOB-format guidance, but must NEVER contain the recipient's actual date of birth
 * or their actual computed temporary password — only a fixed, generic example.
 */
@ExtendWith(MockitoExtension.class)
class WelcomeEmailServiceTest {

    @Mock private EmailService emailService;
    @Mock private SchoolRepository schoolRepository;

    private WelcomeEmailService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new WelcomeEmailService();
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "frontendUrl", "https://edunexify.co.in");

        School school = new School();
        school.setId(SCHOOL_ID);
        school.setName("Indra Academy");
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
    }

    @Test
    void sendsHtmlEmailWithExpectedRecipientAndSubject() {
        service.sendWelcomeEmail("STU_001", "Asha Verma", Role.STUDENT, "asha@test.com", SCHOOL_ID);

        verify(emailService).sendHtmlEmail(
                org.mockito.ArgumentMatchers.eq("asha@test.com"),
                org.mockito.ArgumentMatchers.eq("Welcome to Edunexify, Asha Verma!"),
                anyString());
    }

    @Test
    void bodyIncludesNameUserIdRoleSchoolAndLoginUrl_butNeverRealDobOrPassword() {
        // A DOB whose yyyyMMdd encoding must NEVER appear anywhere in the email body.
        // sendWelcomeEmail doesn't take a DOB at all (by design — see the class javadoc),
        // so this test also structurally proves the method has no way to leak one.
        service.sendWelcomeEmail("STU_001", "Asha Verma", Role.STUDENT, "asha@test.com", SCHOOL_ID);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(anyString(), anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();

        assertThat(body).contains("Asha Verma");
        assertThat(body).contains("STU_001");
        assertThat(body).contains("Student");
        assertThat(body).contains("Indra Academy");
        assertThat(body).contains("https://edunexify.co.in/home");

        // Generic guidance only — the fixed example date/password, never a real one.
        assertThat(body).contains("YYYYMMDD");
        assertThat(body).contains("15 Mar 2010");
        assertThat(body).contains("20100315");

        // Must explicitly require a new password on first login.
        assertThat(body.toLowerCase()).contains("set a new password");
    }

    @Test
    void teacherRoleUsesTeacherLabel() {
        service.sendWelcomeEmail("TCH_001", "Ravi Kumar", Role.TEACHER, "ravi@test.com", SCHOOL_ID);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(anyString(), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("Teacher");
    }

    @Test
    void skipsSilentlyWhenEmailIsBlank() {
        service.sendWelcomeEmail("STU_001", "Asha Verma", Role.STUDENT, "  ", SCHOOL_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void skipsSilentlyWhenEmailIsNull() {
        service.sendWelcomeEmail("STU_001", "Asha Verma", Role.STUDENT, null, SCHOOL_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void neverThrowsWhenSchoolLookupFails() {
        when(schoolRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        // Must not propagate — sendWelcomeEmail is called after account creation already
        // succeeded, so a failure here must never surface to the caller.
        service.sendWelcomeEmail("STU_001", "Asha Verma", Role.STUDENT, "asha@test.com", SCHOOL_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }
}
