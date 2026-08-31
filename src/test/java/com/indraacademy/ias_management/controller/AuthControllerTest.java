package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ChangeInitialPasswordRequest;
import com.indraacademy.ias_management.dto.LoginRequest;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PermissionService;
import com.indraacademy.ias_management.service.WelcomeEmailService;
import com.indraacademy.ias_management.util.JwtUtil;
import com.indraacademy.ias_management.util.SchoolContext;
import com.indraacademy.ias_management.config.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the first-login password-change flow added to AuthController:
 * DOB-derived initial passwords for STUDENT/TEACHER registration (unchanged
 * client-supplied passwords for ADMIN-family roles), the restricted-session
 * login branch (no refresh token, pwdChangeRequired claim), and the
 * change-initial-password endpoint's validation rules.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthService authService;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private AdminRepository adminRepository;
    @Mock private RateLimiter rateLimiter;
    @Mock private PermissionService permissionService;
    @Mock private AuditService auditService;
    @Mock private WelcomeEmailService welcomeEmailService;
    @Mock private com.indraacademy.ias_management.service.PasswordResetService passwordResetService;

    private AuthController controller;
    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() throws Exception {
        controller = new AuthController();
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(controller, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(controller, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(controller, "adminRepository", adminRepository);
        ReflectionTestUtils.setField(controller, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        ReflectionTestUtils.setField(controller, "auditService", auditService);
        ReflectionTestUtils.setField(controller, "welcomeEmailService", welcomeEmailService);
        ReflectionTestUtils.setField(controller, "passwordResetService", passwordResetService);
        ReflectionTestUtils.setField(controller, "isSecure", false);
        ReflectionTestUtils.setField(controller, "sameSite", "Lax");
        ReflectionTestUtils.setField(controller, "accessTokenExpiryMinutes", 15L);
        ReflectionTestUtils.setField(controller, "refreshTokenExpiryDays", 7L);
        ReflectionTestUtils.setField(controller, "cookieDomain", "");

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();
        lenient().when(jwtUtil.getPrivateKey()).thenReturn(keyPair.getPrivate());

        School activeSchool = new School();
        activeSchool.setActive(true);
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(activeSchool));

        SchoolContext.set(SCHOOL_ID);
    }

    @AfterEach
    void tearDown() {
        SchoolContext.clear();
    }

    // ─── registerUser: DOB-derived password for STUDENT/TEACHER ──────────────

    @Test
    void registerStudent_derivesPasswordFromDob_andSetsMustChangePassword() {
        when(authService.getRole()).thenReturn(Role.ADMIN);
        when(userRepository.findByUserId("S1")).thenReturn(Optional.empty());
        Student student = new Student();
        student.setStudentId("S1");
        student.setDob(LocalDate.of(1990, 5, 23));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");

        User request = new User();
        request.setUserId("S1");
        request.setRole(Role.STUDENT);
        // Client-supplied password must be ignored for STUDENT/TEACHER — the endpoint
        // derives it from DOB regardless of what's sent here.
        request.setPassword("ClientSuppliedPassword123");

        ResponseEntity<?> response = controller.registerUser(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(passwordEncoder).encode("19900523");

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getPassword()).isEqualTo("ENCODED");
        assertThat(savedCaptor.getValue().isMustChangePassword()).isTrue();
    }

    @Test
    void registerStudent_sendsWelcomeEmailAfterAccountIsCreated() {
        when(authService.getRole()).thenReturn(Role.ADMIN);
        when(userRepository.findByUserId("S1")).thenReturn(Optional.empty());
        Student student = new Student();
        student.setStudentId("S1");
        student.setName("Asha Verma");
        student.setDob(LocalDate.of(1990, 5, 23));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");

        User request = new User();
        request.setUserId("S1");
        request.setRole(Role.STUDENT);
        request.setEmail("s1@test.com");

        controller.registerUser(request);

        verify(welcomeEmailService).sendWelcomeEmail("S1", "Asha Verma", Role.STUDENT, "s1@test.com", SCHOOL_ID);
    }

    @Test
    void registerStudent_withoutDob_isRejected() {
        when(authService.getRole()).thenReturn(Role.ADMIN);
        when(userRepository.findByUserId("S1")).thenReturn(Optional.empty());
        Student student = new Student();
        student.setStudentId("S1");
        student.setDob(null);
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));

        User request = new User();
        request.setUserId("S1");
        request.setRole(Role.STUDENT);

        ResponseEntity<?> response = controller.registerUser(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("Date of birth is required");
        verify(userRepository, never()).save(any());
        verify(welcomeEmailService, never()).sendWelcomeEmail(any(), any(), any(), any(), any());
    }

    @Test
    void registerAdmin_stillRequiresClientSuppliedStrongPassword() {
        when(authService.getRole()).thenReturn(Role.ADMIN);
        when(userRepository.findByUserId("A1")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");

        User request = new User();
        request.setUserId("A1");
        request.setRole(Role.SUB_ADMIN);
        request.setPassword("StrongPass1");

        ResponseEntity<?> response = controller.registerUser(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(passwordEncoder).encode("StrongPass1");
        verify(studentRepository, never()).findByStudentIdAndSchoolId(any(), any());

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().isMustChangePassword()).isFalse();
        // Welcome email is only for STUDENT/TEACHER accounts, never ADMIN-family roles.
        verify(welcomeEmailService, never()).sendWelcomeEmail(any(), any(), any(), any(), any());
    }

    @Test
    void registerAdmin_withoutPassword_isRejected() {
        when(authService.getRole()).thenReturn(Role.ADMIN);
        when(userRepository.findByUserId("A1")).thenReturn(Optional.empty());

        User request = new User();
        request.setUserId("A1");
        request.setRole(Role.SUB_ADMIN);

        ResponseEntity<?> response = controller.registerUser(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    // ─── login: restricted first-login session ────────────────────────────────

    @Test
    void login_withMustChangePasswordTrue_issuesNoRefreshToken_andFlagsResponse() {
        User user = new User();
        user.setUserId("S1");
        user.setRole(Role.STUDENT);
        user.setPassword("HASHED");
        user.setSchoolId(SCHOOL_ID);
        user.setMustChangePassword(true);

        when(userRepository.findByUserId("S1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("19900523", "HASHED")).thenReturn(true);

        LoginRequest req = new LoginRequest();
        req.setUserId("S1");
        req.setPassword("19900523");

        MockHttpServletResponse httpResponse = new MockHttpServletResponse();
        ResponseEntity<?> response = controller.login(req, httpResponse);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().toString()).contains("mustChangePassword=true");

        // No refresh token cookie should be set for a restricted session.
        assertThat(httpResponse.getHeaders("Set-Cookie"))
                .noneMatch(h -> h.startsWith("refreshToken=") && !h.startsWith("refreshToken=;"));
        assertThat(httpResponse.getHeaders("Set-Cookie"))
                .anyMatch(h -> h.startsWith("accessToken=") && !h.startsWith("accessToken=;"));

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getRefreshTokenId()).isNull();
    }

    @Test
    void login_withMustChangePasswordFalse_issuesRefreshToken() {
        User user = new User();
        user.setUserId("T1");
        user.setRole(Role.TEACHER);
        user.setPassword("HASHED");
        user.setSchoolId(SCHOOL_ID);
        user.setMustChangePassword(false);

        when(userRepository.findByUserId("T1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("MyRealPassword1", "HASHED")).thenReturn(true);

        LoginRequest req = new LoginRequest();
        req.setUserId("T1");
        req.setPassword("MyRealPassword1");

        MockHttpServletResponse httpResponse = new MockHttpServletResponse();
        ResponseEntity<?> response = controller.login(req, httpResponse);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().toString()).contains("mustChangePassword=false");
        assertThat(httpResponse.getHeaders("Set-Cookie"))
                .anyMatch(h -> h.startsWith("refreshToken=") && !h.startsWith("refreshToken=;"));

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getRefreshTokenId()).isNotNull();
    }

    // ─── Generated-ID / legacy-ID login — format-agnostic by design ────────

    @Test
    void generatedStudentId_logsInExactlyLikeAnyOtherUserId() {
        User user = new User();
        user.setUserId("stu_26010001");
        user.setRole(Role.STUDENT);
        user.setPassword("HASHED");
        user.setSchoolId(SCHOOL_ID);
        user.setMustChangePassword(false);

        when(userRepository.findByUserId("stu_26010001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("MyRealPassword1", "HASHED")).thenReturn(true);

        LoginRequest req = new LoginRequest();
        req.setUserId("stu_26010001");
        req.setPassword("MyRealPassword1");

        ResponseEntity<?> response = controller.login(req, new MockHttpServletResponse());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void generatedParentId_logsInExactlyLikeAnyOtherUserId() {
        User user = new User();
        user.setUserId("par_26010001");
        user.setRole(Role.PARENT);
        user.setPassword("HASHED");
        user.setSchoolId(SCHOOL_ID);
        user.setMustChangePassword(false);

        when(userRepository.findByUserId("par_26010001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("MyRealPassword1", "HASHED")).thenReturn(true);

        LoginRequest req = new LoginRequest();
        req.setUserId("par_26010001");
        req.setPassword("MyRealPassword1");

        ResponseEntity<?> response = controller.login(req, new MockHttpServletResponse());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void legacyNonStandardTeacherId_stillLogsIn_backwardCompatibility() {
        // The exact Indra Academy scenario: an existing teacher ID predating this feature,
        // in an arbitrary SWEEDU-era format. Nothing in the login path cares about format.
        User user = new User();
        user.setUserId("EMP123");
        user.setRole(Role.TEACHER);
        user.setPassword("HASHED");
        user.setSchoolId(SCHOOL_ID);
        user.setMustChangePassword(false);

        when(userRepository.findByUserId("EMP123")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("MyRealPassword1", "HASHED")).thenReturn(true);

        LoginRequest req = new LoginRequest();
        req.setUserId("EMP123");
        req.setPassword("MyRealPassword1");

        ResponseEntity<?> response = controller.login(req, new MockHttpServletResponse());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ─── reset-password clears mustChangePassword (Parent Option A onboarding) ──

    @Test
    void resetPassword_success_alsoClearsMustChangePassword() {
        // This is what makes Parent Option A onboarding actually work end-to-end: a newly
        // created parent's account has mustChangePassword=true and an unknown placeholder
        // password. Setting their real password via this emailed-token flow must be the
        // ONLY step needed — without this, they'd be forced through change-initial-password
        // again immediately after, having just set a real password through a verified link.
        User user = new User();
        user.setUserId("par_26010001");
        user.setRole(Role.PARENT);
        user.setResetToken("HASHED_TOKEN");
        user.setResetTokenExpiry(new java.util.Date(System.currentTimeMillis() + 60000));
        user.setMustChangePassword(true);

        when(passwordResetService.hashToken("raw-token")).thenReturn("HASHED_TOKEN");
        when(userRepository.findByResetToken("HASHED_TOKEN")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("NEW_HASHED");

        HashMap<String, String> body = new HashMap<>();
        body.put("newPassword", "BrandNewPassw0rd");
        ResponseEntity<?> response = controller.resetPassword("raw-token", body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().isMustChangePassword()).isFalse();
    }

    // ─── change-initial-password ───────────────────────────────────────────────

    private User restrictedUser() {
        User user = new User();
        user.setUserId("S1");
        user.setRole(Role.STUDENT);
        user.setSchoolId(SCHOOL_ID);
        user.setPassword("HASHED_TEMP");
        user.setMustChangePassword(true);
        return user;
    }

    @Test
    void changeInitialPassword_mismatchedConfirmation_isRejected() {
        when(authService.getUserId()).thenReturn("S1");
        when(userRepository.findByUserId("S1")).thenReturn(Optional.of(restrictedUser()));

        ChangeInitialPasswordRequest req = new ChangeInitialPasswordRequest();
        req.setNewPassword("NewStrong1");
        req.setConfirmPassword("Different1");

        ResponseEntity<?> response = controller.changeInitialPassword(req, new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("do not match");
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeInitialPassword_sameAsTemporaryPassword_isRejected() {
        when(authService.getUserId()).thenReturn("S1");
        User user = restrictedUser();
        when(userRepository.findByUserId("S1")).thenReturn(Optional.of(user));
        // Strength-valid candidate that happens to match the stored (temporary) hash —
        // isolates the same-as-temporary-password check from the strength check, since
        // a real DOB-derived temp password (e.g. "19900523") would always fail strength
        // validation first and never reach this check at all.
        when(passwordEncoder.matches("NewStrong1", "HASHED_TEMP")).thenReturn(true);

        ChangeInitialPasswordRequest req = new ChangeInitialPasswordRequest();
        req.setNewPassword("NewStrong1");
        req.setConfirmPassword("NewStrong1");

        ResponseEntity<?> response = controller.changeInitialPassword(req, new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("different from your temporary password");
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeInitialPassword_weakPassword_isRejected() {
        when(authService.getUserId()).thenReturn("S1");
        when(userRepository.findByUserId("S1")).thenReturn(Optional.of(restrictedUser()));

        ChangeInitialPasswordRequest req = new ChangeInitialPasswordRequest();
        req.setNewPassword("weakpass");
        req.setConfirmPassword("weakpass");

        ResponseEntity<?> response = controller.changeInitialPassword(req, new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeInitialPassword_success_clearsFlagAndInvalidatesSession() {
        when(authService.getUserId()).thenReturn("S1");
        User user = restrictedUser();
        user.setRefreshTokenId("some-jti");
        when(userRepository.findByUserId("S1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("NewStrong1", "HASHED_TEMP")).thenReturn(false);
        when(passwordEncoder.encode("NewStrong1")).thenReturn("NEW_ENCODED");
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.empty());

        ChangeInitialPasswordRequest req = new ChangeInitialPasswordRequest();
        req.setNewPassword("NewStrong1");
        req.setConfirmPassword("NewStrong1");

        MockHttpServletResponse httpResponse = new MockHttpServletResponse();
        ResponseEntity<?> response = controller.changeInitialPassword(req, httpResponse);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().toString()).contains("sign in with your new password");

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        User saved = savedCaptor.getValue();
        assertThat(saved.getPassword()).isEqualTo("NEW_ENCODED");
        assertThat(saved.isMustChangePassword()).isFalse();
        assertThat(saved.getRefreshTokenId()).isNull();

        // The restricted access token cookie must be cleared — no new session is granted.
        assertThat(httpResponse.getHeaders("Set-Cookie"))
                .anyMatch(h -> h.startsWith("accessToken=;") || h.contains("Max-Age=0"));
    }
}
