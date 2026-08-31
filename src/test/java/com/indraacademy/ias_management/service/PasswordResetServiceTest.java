package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService();
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "frontendUrl", "https://edunexify.co.in");
    }

    @Test
    void parentWelcomeUsesBrandedWelcomeTemplateAndEscapesEveryDynamicAccountValue() {
        User user = user("PAR_<1001>&\"'", "parent@example.com");

        service.sendParentWelcomeLink(user, "Asha & <Family> \"Parent\"", "Doon & <Valley> \"School\"");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("parent@example.com"),
                eq("Welcome to Edunexify – Set Your Password"), html.capture());

        assertThat(html.getValue())
                .contains("<title>Welcome to Edunexify</title>")
                .contains("Welcome to Edunexify!")
                .contains("Your Parent Account is Ready")
                .contains("Set My Password")
                .contains("expires in <strong>1 hour</strong>")
                .contains("Asha &amp; &lt;Family&gt; &quot;Parent&quot;")
                .contains("PAR_&lt;1001&gt;&amp;&quot;&#39;")
                .contains("Doon &amp; &lt;Valley&gt; &quot;School&quot;")
                .contains("<span style=\"font-size:12px;color:#9ca3af;\">Administration</span>")
                .contains("background:linear-gradient(135deg,#0f172a 0%,#1f6f8b 55%,#4fbdbd 100%)")
                .contains("/reset-password?token=");
        assertThat(html.getValue()).doesNotContain("<title>Password Reset</title>");

        assertThat(user.getResetToken()).hasSize(64);
        assertThat(user.getResetTokenExpiry()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void forgotPasswordRetainsResetSpecificTitleCopyAndAction() {
        User user = user("STU_001", "student@example.com");
        String intro = "We received a request to reset your password. This link expires in <strong>1 hour</strong>.";

        service.sendResetLink(user, "Password Reset Request – Edunexify", intro);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("student@example.com"),
                eq("Password Reset Request – Edunexify"), html.capture());

        assertThat(html.getValue())
                .contains("<title>Password Reset Request - Edunexify</title>")
                .contains("Password Reset Request")
                .contains("Reset My Password")
                .contains(intro)
                .contains("You can safely ignore this email")
                .contains("/reset-password?token=")
                .doesNotContain("Your Parent Account is Ready")
                .doesNotContain("Set My Password")
                .doesNotContain("Parent ID");
        verify(userRepository).save(user);
    }

    private User user(String userId, String email) {
        User user = new User();
        user.setUserId(userId);
        user.setEmail(email);
        return user;
    }
}
