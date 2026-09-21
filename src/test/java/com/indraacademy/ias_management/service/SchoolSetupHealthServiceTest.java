package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SchoolSetupHealthResponse;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.BoardType;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchoolSetupHealthServiceTest {
    @Mock SecurityUtil securityUtil;
    @Mock SchoolRepository schoolRepository;
    @Mock AcademicSessionRepository sessionRepository;
    @Mock TeacherRepository teacherRepository;
    @Mock StudentRepository studentRepository;
    @Mock TimetableRepository timetableRepository;
    @Mock ParentStudentRelationshipRepository parentRepository;
    @Mock FeeStructureRuleRepository feeRuleRepository;

    private SchoolSetupHealthService service;
    private School school;

    @BeforeEach
    void setUp() {
        service = new SchoolSetupHealthService(securityUtil, schoolRepository, sessionRepository,
                teacherRepository, studentRepository, timetableRepository, parentRepository,
                feeRuleRepository);
        school = new School();
        school.setName("Test School");
        when(securityUtil.getSchoolId()).thenReturn(42L);
        when(schoolRepository.findById(42L)).thenReturn(Optional.of(school));
        when(sessionRepository.findBySchoolIdAndCurrentTrue(42L)).thenReturn(Optional.empty());
    }

    @Test
    void freshSchoolHasNoRequiredProgressAndParentContactsAreNotApplicable() {
        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(result.completionPercentage()).isZero();
        assertThat(result.completedRequired()).isZero();
        assertThat(result.totalRequired()).isEqualTo(5);
        assertThat(result.status()).isEqualTo(SchoolSetupHealthResponse.Status.NOT_STARTED);

        // Every required item is INCOMPLETE on a truly fresh school (a bare `new School()` with
        // only a name — no board, no session, no teachers, no attendance config, no timetable).
        for (String requiredKey : new String[]{"SCHOOL_PROFILE", "ACADEMIC_SESSION", "TEACHERS",
                "ATTENDANCE_CONFIGURATION", "TIMETABLE"}) {
            assertThat(item(result, requiredKey).status())
                    .as("required item %s", requiredKey)
                    .isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        }
        // Recommended/optional items are incomplete too, except parent contacts — with zero
        // active students, that one is NOT_APPLICABLE rather than a false "incomplete" nag.
        assertThat(item(result, "SCHOOL_LOGO").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(item(result, "STUDENTS").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(item(result, "PARENT_CONTACTS").status())
                .isEqualTo(SchoolSetupHealthResponse.ItemStatus.NOT_APPLICABLE);
        assertThat(item(result, "FEE_CONFIGURATION").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(result.items()).noneMatch(i -> i.key().equals("NOTIFICATION_CONFIGURATION"));

        verify(parentRepository, never()).countStudentsWithActiveParentContact(anyLong());
    }

    @Test
    void profileCompletionIsBasedOnMeaningfulContactAndIdentityFields() {
        completeProfileAndAttendance(school);

        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(item(result, "SCHOOL_PROFILE").status())
                .isEqualTo(SchoolSetupHealthResponse.ItemStatus.COMPLETED);
        assertThat(item(result, "ATTENDANCE_CONFIGURATION").status())
                .isEqualTo(SchoolSetupHealthResponse.ItemStatus.COMPLETED);
        assertThat(result.completionPercentage()).isEqualTo(40);
    }

    @Test
    void profileCompletionDoesNotRequireOptionalContactFields() {
        // Address/city/state/pincode/email/phone are all genuinely optional at school
        // onboarding (neither SchoolOnboardRequest's bean validation nor the SUPER_ADMIN
        // onboarding form require them) — only name (always present) and board type (a real
        // academic classification, not contact metadata) gate School Profile completion.
        school.setBoardType(BoardType.CBSE);

        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(item(result, "SCHOOL_PROFILE").status())
                .isEqualTo(SchoolSetupHealthResponse.ItemStatus.COMPLETED);
    }

    @Test
    void profileIncompleteWithoutBoardTypeEvenWhenAllContactFieldsPresent() {
        school.setAddress("1 School Road");
        school.setCity("Delhi");
        school.setState("Delhi");
        school.setPincode("110001");
        school.setEmail("school@example.com");
        school.setPhone("9999999999");
        // boardType deliberately left null

        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(item(result, "SCHOOL_PROFILE").status())
                .isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
    }

    @Test
    void currentSessionScopesTimetableAndFeeChecksToThatExactSession() {
        AcademicSession session = new AcademicSession();
        session.setId(7L);
        session.setLabel("2026-2027");
        when(sessionRepository.findBySchoolIdAndCurrentTrue(42L)).thenReturn(Optional.of(session));
        when(timetableRepository.existsByAcademicSessionIdAndSchoolId(7L, 42L)).thenReturn(true);
        when(feeRuleRepository.existsBySchoolIdAndAcademicSessionId(42L, 7L)).thenReturn(true);

        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(item(result, "ACADEMIC_SESSION").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.COMPLETED);
        assertThat(item(result, "TIMETABLE").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.COMPLETED);
        assertThat(item(result, "FEE_CONFIGURATION").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.COMPLETED);
    }

    @Test
    void noCurrentSession_timetableAndFeeChecksAreIncomplete_withoutQueryingEitherRepository() {
        // sessionRepository already stubbed to Optional.empty() in setUp(). Neither the
        // timetable nor the fee query should ever run against a null/absent session id.
        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(item(result, "ACADEMIC_SESSION").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(item(result, "TIMETABLE").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(item(result, "FEE_CONFIGURATION").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);

        verify(timetableRepository, never()).existsByAcademicSessionIdAndSchoolId(any(), any());
        verify(feeRuleRepository, never()).existsBySchoolIdAndAcademicSessionId(any(), any());
    }

    @Test
    void studentsRequireParentCoverageOnlyWhenPresent() {
        when(studentRepository.countByStatusAndSchoolId(StudentStatus.ACTIVE, 42L)).thenReturn(3L);
        when(parentRepository.countStudentsWithActiveParentContact(42L)).thenReturn(2L);

        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(item(result, "STUDENTS").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.COMPLETED);
        assertThat(item(result, "PARENT_CONTACTS").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(item(result, "PARENT_CONTACTS").description()).contains("1 active student");
    }

    @Test
    void fullyConfiguredSchoolIsReadyWhileOptionalItemsDoNotAffectScore() {
        completeProfileAndAttendance(school);
        school.setLogoUrl("schools/42/logo.png");
        AcademicSession session = new AcademicSession();
        session.setId(7L);
        session.setLabel("2026-2027");
        when(sessionRepository.findBySchoolIdAndCurrentTrue(42L)).thenReturn(Optional.of(session));
        when(teacherRepository.countBySchoolIdAndStatus(42L, TeacherStatus.ACTIVE)).thenReturn(4L);
        when(timetableRepository.existsByAcademicSessionIdAndSchoolId(7L, 42L)).thenReturn(true);

        SchoolSetupHealthResponse result = service.getSetupHealth();

        // All 5 required items complete -> 100% / READY, exactly like section 13's example, even
        // though Students (recommended) and Fees/Notifications (optional) are left incomplete —
        // and with zero active students, Parent Contacts is correctly NOT_APPLICABLE rather than
        // a false "incomplete" nag. None of these non-required gaps may reduce the score.
        assertThat(result.completionPercentage()).isEqualTo(100);
        assertThat(result.completedRequired()).isEqualTo(5);
        assertThat(result.totalRequired()).isEqualTo(5);
        assertThat(result.status()).isEqualTo(SchoolSetupHealthResponse.Status.READY);
        assertThat(item(result, "STUDENTS").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(item(result, "PARENT_CONTACTS").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.NOT_APPLICABLE);
        assertThat(item(result, "FEE_CONFIGURATION").status()).isEqualTo(SchoolSetupHealthResponse.ItemStatus.INCOMPLETE);
        assertThat(result.items()).noneMatch(i -> i.key().equals("NOTIFICATION_CONFIGURATION"));
    }

    @Test
    void partialRequiredCompletion_isInProgress_withFlooredPercentage() {
        // 2 of 5 required items complete: profile + teachers. floor(2*100/5) = 40, and since
        // completedRequired is neither 0 nor totalRequired, status must be IN_PROGRESS — never
        // NOT_STARTED or READY.
        school.setBoardType(BoardType.CBSE);
        when(teacherRepository.countBySchoolIdAndStatus(42L, TeacherStatus.ACTIVE)).thenReturn(1L);

        SchoolSetupHealthResponse result = service.getSetupHealth();

        assertThat(result.completedRequired()).isEqualTo(2);
        assertThat(result.totalRequired()).isEqualTo(5);
        assertThat(result.completionPercentage()).isEqualTo(40);
        assertThat(result.status()).isEqualTo(SchoolSetupHealthResponse.Status.IN_PROGRESS);
    }

    @Test
    void completionPercentageIsAlwaysAnIntegerBetweenZeroAndOneHundredInclusive() {
        // A non-exact division (1 of 5 = 20, but exercised via 3 of 5 = 60, which does not
        // divide evenly against typical rounding expectations) still floors cleanly and never
        // produces a value outside [0, 100] or a fractional/rounded-up result.
        school.setBoardType(BoardType.CBSE);
        when(teacherRepository.countBySchoolIdAndStatus(42L, TeacherStatus.ACTIVE)).thenReturn(1L);
        AcademicSession session = new AcademicSession();
        session.setId(7L);
        when(sessionRepository.findBySchoolIdAndCurrentTrue(42L)).thenReturn(Optional.of(session));

        SchoolSetupHealthResponse result = service.getSetupHealth();

        // 3 of 5 required (profile, session, teachers) -> floor(3*100/5) = 60, not 61/rounded.
        assertThat(result.completedRequired()).isEqualTo(3);
        assertThat(result.completionPercentage()).isEqualTo(60);
        assertThat(result.completionPercentage()).isBetween(0, 100);
        assertThat(result.status()).isEqualTo(SchoolSetupHealthResponse.Status.IN_PROGRESS);
    }

    @Test
    void everyQueryUsesAuthenticatedSchoolAndEndpointPerformsNoWrites() {
        service.getSetupHealth();

        verify(schoolRepository).findById(42L);
        verify(sessionRepository).findBySchoolIdAndCurrentTrue(42L);
        verify(teacherRepository).countBySchoolIdAndStatus(42L, TeacherStatus.ACTIVE);
        verify(studentRepository).countByStatusAndSchoolId(StudentStatus.ACTIVE, 42L);
        verifyNoMoreInteractions(parentRepository);

        // Purely derived/read-only — no AcademicSession, fee, or School mutation, no
        // onboarding/completion state written anywhere.
        verify(schoolRepository, never()).save(any());
        verify(schoolRepository, never()).saveAndFlush(any());
        verify(schoolRepository, never()).delete(any());
        verify(sessionRepository, never()).save(any());
        verify(teacherRepository, never()).save(any());
        verify(studentRepository, never()).save(any());
        verify(timetableRepository, never()).save(any());
        verify(feeRuleRepository, never()).save(any());
    }

    private SchoolSetupHealthResponse.Item item(SchoolSetupHealthResponse response, String key) {
        return response.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    private void completeProfileAndAttendance(School value) {
        value.setBoardType(BoardType.CBSE);
        value.setAddress("1 School Road");
        value.setCity("Delhi");
        value.setState("Delhi");
        value.setPincode("110001");
        value.setEmail("school@example.com");
        value.setPhone("9999999999");
        value.setWorkingDays("MONDAY,TUESDAY");
        value.setTimezone("Asia/Kolkata");
        value.setSchoolStartTime(LocalTime.of(8, 0));
        value.setCheckinWindowStart(LocalTime.of(7, 30));
        value.setCheckinWindowEnd(LocalTime.of(9, 0));
    }
}
