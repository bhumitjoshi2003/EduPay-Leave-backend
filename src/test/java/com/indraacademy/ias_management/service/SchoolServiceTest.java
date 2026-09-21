package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SchoolSettingsResponse;
import com.indraacademy.ias_management.dto.SchoolSettingsUpdateRequest;
import com.indraacademy.ias_management.dto.SuperAdminDashboardDto;
import com.indraacademy.ias_management.dto.SuperAdminSchoolUpdateRequest;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SubscriptionPlan;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.RefundRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integrity-hardening phase: the platform-wide SUPER_ADMIN revenue figure had never been
 * netted against refunds at all — a distinct gap from (and missed by) the earlier
 * school-scoped DashboardService fix, since SchoolService.getSuperAdminDashboard queries the
 * platform-wide Payment/Refund sums, not the school-scoped ones.
 */
@ExtendWith(MockitoExtension.class)
class SchoolServiceTest {

    @Mock private SchoolRepository schoolRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private com.indraacademy.ias_management.repository.UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private EntitlementRefreshService entitlementRefreshService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HttpServletRequest request;
    @Mock private ObjectStorageService objectStorageService;

    private SchoolService service;

    private static final Long SCHOOL_ID = 2L;

    @BeforeEach
    void setUp() {
        service = new SchoolService();
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "refundRepository", refundRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "entitlementRefreshService", entitlementRefreshService);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(service, "objectStorageService", objectStorageService);

        lenient().when(schoolRepository.count()).thenReturn(5L);
        lenient().when(schoolRepository.countByActiveTrue()).thenReturn(4L);
        lenient().when(studentRepository.count()).thenReturn(100L);
        lenient().when(teacherRepository.count()).thenReturn(10L);
        lenient().when(securityUtil.getUsername()).thenReturn("super@edunexify.co.in");
        lenient().when(securityUtil.getRole()).thenReturn("SUPER_ADMIN");
        lenient().when(userRepository.findFirstBySchoolIdAndRole(any(), any())).thenReturn(Optional.empty());
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    void superAdminRevenue_noRefunds_equalsGrossPlatformWidePayments() {
        LocalDate today = LocalDate.now();
        when(paymentRepository.sumAmountCollectedByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(1_000_000L);
        when(refundRepository.sumAmountPaiseByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(0L);

        SuperAdminDashboardDto dto = service.getSuperAdminDashboard();

        assertThat(dto.getRevenueThisMonth()).isEqualTo(1_000_000L);
    }

    @Test
    void superAdminRevenue_afterRefunds_isNetOfRefundsAcrossAllSchools() {
        LocalDate today = LocalDate.now();
        when(paymentRepository.sumAmountCollectedByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(1_000_000L);
        when(refundRepository.sumAmountPaiseByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(150_000L);

        SuperAdminDashboardDto dto = service.getSuperAdminDashboard();

        assertThat(dto.getRevenueThisMonth()).isEqualTo(850_000L);
        // Never count refunded money as collected revenue — the net figure must be strictly
        // less than the gross figure whenever any refund occurred.
        assertThat(dto.getRevenueThisMonth()).isLessThan(1_000_000L);
    }

    // ─── School.timezone — every teacher-attendance date/time decision depends on this being real ───

    private School existingSchool() {
        School s = new School();
        s.setId(SCHOOL_ID);
        s.setName("Test School");
        s.setTimezone("Asia/Kolkata");
        return s;
    }

    @Test
    void updateSettings_acceptsAValidIanaTimezone() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTimezone("America/Los_Angeles");

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.getTimezone()).isEqualTo("America/Los_Angeles");
    }

    /**
     * A bad timezone string must be rejected at the point of entry — TeacherAttendanceService's
     * own fallback (see its zoneId() Javadoc) is a defensive backstop for data that predates this
     * validation, not a reason to skip validating new writes.
     */
    @Test
    void updateSettings_rejectsAnInvalidTimezone_ratherThanSilentlyStoringIt() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTimezone("Narnia/Cair_Paravel");

        assertThatThrownBy(() -> service.updateSettings(req, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid timezone");

        org.mockito.Mockito.verify(schoolRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void updateSettings_omittedTimezone_leavesTheExistingValueUnchanged() {
        School existing = existingSchool(); // already "Asia/Kolkata"
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setName("Renamed School"); // unrelated field changed, timezone untouched

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.getTimezone()).isEqualTo("Asia/Kolkata");
    }

    // ─── Teacher attendance reminder settings ───────────────────────────────────

    @Test
    void updateSettings_reminderDisabledByDefault_onABrandNewSchool() {
        School existing = existingSchool(); // never touched the reminder fields
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setName("Unrelated update"); // reminder fields never sent

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.isTeacherAttendanceReminderEnabled()).isFalse();
        assertThat(response.getTeacherAttendanceReminderTime()).isNull();
    }

    @Test
    void updateSettings_enablingTheReminderWithoutATime_isRejected() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(true);
        // no reminder time supplied, and none previously set

        assertThatThrownBy(() -> service.updateSettings(req, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reminder time is required");
        verify(schoolRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void updateSettings_enablingTheReminderWithATime_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(true);
        req.setTeacherAttendanceReminderTime("07:45");

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.isTeacherAttendanceReminderEnabled()).isTrue();
        assertThat(response.getTeacherAttendanceReminderTime()).isEqualTo("07:45");
    }

    // Every settings save publishes the reschedule event unconditionally — the
    // TeacherAttendanceReminderDynamicScheduler listener (AFTER_COMMIT-gated) decides for itself
    // whether anything actually needs rescheduling by reloading current settings, so this method
    // never needs to diff which field changed.
    @Test
    void updateSettings_publishesTheScheduleChangedEventUnconditionally_evenForAnUnrelatedFieldChange() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setName("Unrelated update"); // reminder fields never sent

        service.updateSettings(req, request);

        verify(eventPublisher).publishEvent(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));
    }

    @Test
    void updateSettings_rejectedUpdate_neverPublishesTheScheduleChangedEvent() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(true);
        // no reminder time supplied — updateSettings throws before ever saving or publishing

        assertThatThrownBy(() -> service.updateSettings(req, request)).isInstanceOf(IllegalArgumentException.class);
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    void updateSettings_disablingTheReminder_acceptsANullTime_leavingItUnset() {
        School existing = existingSchool();
        existing.setTeacherAttendanceReminderEnabled(true);
        existing.setTeacherAttendanceReminderTime(java.time.LocalTime.of(7, 45));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(false);
        req.setTeacherAttendanceReminderTime(""); // explicit clear

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.isTeacherAttendanceReminderEnabled()).isFalse();
        assertThat(response.getTeacherAttendanceReminderTime()).isNull();
    }

    @Test
    void updateSettings_disablingWithoutClearingTheTime_isAllowed_timeSurvivesForNextEnable() {
        School existing = existingSchool();
        existing.setTeacherAttendanceReminderEnabled(true);
        existing.setTeacherAttendanceReminderTime(java.time.LocalTime.of(7, 45));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(false); // time field simply not sent

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.isTeacherAttendanceReminderEnabled()).isFalse();
        assertThat(response.getTeacherAttendanceReminderTime()).isEqualTo("07:45");
    }

    @Test
    void updateSettings_reminderSettingsRoundTripThroughTheApi() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(true);
        req.setTeacherAttendanceReminderTime("08:15");

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.isTeacherAttendanceReminderEnabled()).isTrue();
        assertThat(response.getTeacherAttendanceReminderTime()).isEqualTo("08:15");
        // Untouched fields round-trip unchanged alongside the new ones.
        assertThat(response.getTimezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void updateSettings_reminderChange_leavesSchoolTimezoneUnchanged() {
        School existing = existingSchool(); // "Asia/Kolkata"
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(true);
        req.setTeacherAttendanceReminderTime("06:30");

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.getTimezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void updateSettings_reminderChange_leavesUnrelatedSettingsUnchanged() {
        School existing = existingSchool();
        existing.setWorkingDays("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY");
        existing.setLateThresholdMinutes(10);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTeacherAttendanceReminderEnabled(true);
        req.setTeacherAttendanceReminderTime("07:00");

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.getWorkingDays()).isEqualTo("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY");
        assertThat(response.getLateThresholdMinutes()).isEqualTo(10);
    }

    // ─── Legacy fields are display-only now (subscription reset) ───────────────
    //
    // SchoolService.updateSubscription() (the old PATCH endpoint) has been removed entirely — it
    // had zero callers in web or Android and was fully superseded by SubscriptionController's
    // POST/PUT .../schools/{id}/subscription. updateSchoolDetails() still accepts plan/
    // maxStudents/expiryDate (the Super Admin "Edit School" form still has these fields) but they
    // no longer drive the entitlement system — SubscriptionController is the sole write path for
    // that now. These tests prove updateSchoolDetails() still persists the legacy display fields
    // but never touches EntitlementRefreshService.

    @Test
    void updateSchoolDetails_planChange_persistsLegacyDisplayField_butNeverTouchesEntitlements() {
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SuperAdminSchoolUpdateRequest req = new SuperAdminSchoolUpdateRequest();
        req.setPlan(SubscriptionPlan.ENTERPRISE);
        LocalDate expiry = LocalDate.now().plusYears(1);
        req.setExpiryDate(expiry);

        SchoolSettingsResponse response = service.updateSchoolDetails(SCHOOL_ID, req, request);

        assertThat(response.getPlan()).isEqualTo(SubscriptionPlan.ENTERPRISE);
        assertThat(response.getExpiryDate()).isEqualTo(expiry);
        verify(entitlementRefreshService, org.mockito.Mockito.never()).refresh(any(), any());
        verify(entitlementRefreshService, org.mockito.Mockito.never()).refreshForSubscriptionChange(any());
    }

    @Test
    void updateSchoolDetails_unrelatedFieldOnly_leavesLegacyPlanFieldUnchanged() {
        School existing = existingSchool();
        existing.setPlan(SubscriptionPlan.TRIAL);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SuperAdminSchoolUpdateRequest req = new SuperAdminSchoolUpdateRequest();
        req.setCity("Pune"); // no plan/expiryDate touched

        SchoolSettingsResponse response = service.updateSchoolDetails(SCHOOL_ID, req, request);

        assertThat(response.getPlan()).isEqualTo(SubscriptionPlan.TRIAL);
    }

    // ─── Phase 2: object-storage logo/report-card-header display resolution ────────────────

    @Test
    void getSettings_objectStorageKeys_resolvedToFreshPresignedUrls() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        School school = existingSchool();
        school.setLogoUrl("schools/2/school/logo/uuid.png");
        school.setReportCardHeaderImageUrl("schools/2/school/report-card-header/uuid.png");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(objectStorageService.resolveDisplayUrl("schools/2/school/logo/uuid.png"))
                .thenReturn("https://storage.example/logo-get-url");
        when(objectStorageService.resolveDisplayUrl("schools/2/school/report-card-header/uuid.png"))
                .thenReturn("https://storage.example/header-get-url");

        SchoolSettingsResponse response = service.getSettings();

        assertThat(response.getLogoUrl()).isEqualTo("https://storage.example/logo-get-url");
        assertThat(response.getReportCardHeaderImageUrl()).isEqualTo("https://storage.example/header-get-url");
    }

    @Test
    void getSettings_legacyLocalDiskValues_leftCompletelyUntouched() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        School school = existingSchool();
        school.setLogoUrl("/uploads/school-logos/2.png");
        school.setReportCardHeaderImageUrl(null);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(objectStorageService.resolveDisplayUrl("/uploads/school-logos/2.png")).thenReturn("/uploads/school-logos/2.png");
        when(objectStorageService.resolveDisplayUrl(null)).thenReturn(null);

        SchoolSettingsResponse response = service.getSettings();

        assertThat(response.getLogoUrl()).isEqualTo("/uploads/school-logos/2.png");
        assertThat(response.getReportCardHeaderImageUrl()).isNull();
    }

    @Test
    void removeReportCardHeader_objectStorageKey_deletedViaObjectStorage_notLocalDisk() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(securityUtil.getRole()).thenReturn("ADMIN");
        School school = existingSchool();
        school.setReportCardHeaderImageUrl("schools/2/school/report-card-header/old.png");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        service.removeReportCardHeader(request);

        assertThat(school.getReportCardHeaderImageUrl()).isNull();
        verify(schoolRepository).save(school);
        verify(objectStorageService).deleteObjectQuietly("schools/2/school/report-card-header/old.png");
    }

    @Test
    void removeReportCardHeader_legacyLocalDiskValue_neverPassedToObjectStorageDelete() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(securityUtil.getRole()).thenReturn("ADMIN");
        School school = existingSchool();
        school.setReportCardHeaderImageUrl("/uploads/report-card-headers/2.png");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        ReflectionTestUtils.setField(service, "headerDirectory", System.getProperty("java.io.tmpdir"));

        service.removeReportCardHeader(request);

        assertThat(school.getReportCardHeaderImageUrl()).isNull();
        verify(objectStorageService, org.mockito.Mockito.never()).deleteObjectQuietly(any());
    }
}
