package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ParentBulkImportDtos.*;
import com.indraacademy.ias_management.entity.Parent;
import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.ParentRepository;
import com.indraacademy.ias_management.repository.ParentStudentRelationshipRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers Parent Bulk Import's matching/conflict rules, sibling handling, and the
 * preview/confirm split. Every scenario from the approved Phase 1 spec (§14) is exercised
 * here: safe auto-match, both kinds of conflict, sibling grouping into one Parent, duplicate
 * relationship rejection, invalid/cross-school student handling, and Standard Access defaults.
 */
@ExtendWith(MockitoExtension.class)
class ParentBulkImportServiceTest {

    private static final Long SCHOOL_ID = 5L;

    @Mock private ParentRepository parentRepository;
    @Mock private ParentStudentRelationshipRepository relationshipRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SecurityUtil securityUtil;
    @Mock private AuditService auditService;
    @Mock private IdGeneratorService idGeneratorService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private SchoolRepository schoolRepository;
    @Mock private HttpServletRequest request;

    private ParentBulkImportService service;

    @BeforeEach
    void setUp() {
        service = new ParentBulkImportService();
        ReflectionTestUtils.setField(service, "parentRepository", parentRepository);
        ReflectionTestUtils.setField(service, "relationshipRepository", relationshipRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "idGeneratorService", idGeneratorService);
        ReflectionTestUtils.setField(service, "passwordResetService", passwordResetService);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID)).thenReturn(List.of());
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school()));
        AtomicInteger seq = new AtomicInteger(1);
        lenient().when(idGeneratorService.generateParentId())
                .thenAnswer(inv -> String.format("par_260100%02d", seq.getAndIncrement()));
        lenient().when(parentRepository.save(any(Parent.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(relationshipRepository.save(any(ParentStudentRelationship.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private School school() {
        School s = new School();
        s.setName("Indra Academy");
        return s;
    }

    private MockMultipartFile csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(String.join(",", ParentBulkImportService.TEMPLATE_HEADERS)).append("\n");
        for (String row : dataRows) sb.append(row).append("\n");
        return new MockMultipartFile("file", "parents.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Student activeStudent(String id, String name, String className) {
        Student s = new Student();
        s.setStudentId(id);
        s.setName(name);
        s.setClassName(className);
        s.setStatus(StudentStatus.ACTIVE);
        return s;
    }

    private void givenStudent(String id, Student student) {
        when(studentRepository.findByStudentIdAndSchoolId(id, SCHOOL_ID)).thenReturn(Optional.ofNullable(student));
    }

    private Parent existingParent(String parentId, String name, String phone, String email) {
        Parent p = new Parent();
        p.setParentId(parentId);
        p.setSchoolId(SCHOOL_ID);
        p.setName(name);
        p.setPhoneNumber(phone);
        p.setEmail(email);
        p.setActive(true);
        return p;
    }

    // ─── Matching rules ─────────────────────────────────────────────────────

    @Test
    void samePhoneAndSameEmail_isASafeAutoMatch() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "rajesh@example.com")));

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father"));

        assertThat(preview.rows()).hasSize(1);
        RowPreview row = preview.rows().get(0);
        assertThat(row.status()).isEqualTo(RowStatus.VALID_EXISTING_PARENT_MATCH);
        assertThat(row.matchedParentId()).isEqualTo("par_26010001");
        assertThat(preview.existingParentMatchCount()).isEqualTo(1);
    }

    @Test
    void samePhoneDifferentEmail_isFlaggedAsConflict_neverAutoMerged() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "old-email@example.com")));

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,new-email@example.com,stu_1,Father"));

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.CONFLICT_PHONE_MATCH_EMAIL_DIFFERS);
        assertThat(preview.conflictCount()).isEqualTo(1);
    }

    @Test
    void sameEmailDifferentPhone_isFlaggedAsConflict_neverAutoMerged() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876500000", "rajesh@example.com")));

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father"));

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.CONFLICT_EMAIL_MATCH_PHONE_DIFFERS);
        assertThat(preview.conflictCount()).isEqualTo(1);
    }

    @Test
    void conflictRow_withNoAdminResolution_isSkippedAtConfirm_neverGuessed() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "old-email@example.com")));
        MockMultipartFile file = csv("Rajesh Joshi,9876543210,new-email@example.com,stu_1,Father");

        ConfirmResponse result = service.confirm(file, new ConfirmRequest(Map.of()), request);

        assertThat(result.parentsCreated()).isZero();
        assertThat(result.relationshipsCreated()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(parentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void conflictRow_explicitlyResolvedByAdminAsLinkExisting_isImported() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "old-email@example.com")));
        when(parentRepository.findByParentIdAndSchoolId("par_26010001", SCHOOL_ID))
                .thenReturn(Optional.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "old-email@example.com")));
        MockMultipartFile file = csv("Rajesh Joshi,9876543210,new-email@example.com,stu_1,Father");

        ConfirmRequest confirmRequest = new ConfirmRequest(Map.of(2, new RowResolution(RowAction.LINK_EXISTING, "par_26010001")));
        ConfirmResponse result = service.confirm(file, confirmRequest, request);

        assertThat(result.parentsCreated()).isZero(); // reused the existing parent, no new one
        assertThat(result.relationshipsCreated()).isEqualTo(1);
        verify(idGeneratorService, org.mockito.Mockito.never()).generateParentId();
    }

    @Test
    void twoConflictRowsSharingIdentity_bothResolvedAsCreateNew_stillCreateOnlyOneParent() {
        // Both rows share the identical phone+email and both conflict against the SAME
        // existing parent (phone matches, email differs) — if the admin reviews both and
        // decides "no, this is genuinely a different, new parent" for each, they must still
        // end up as ONE new parent with two relationships, not two separate accounts.
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        givenStudent("stu_2", activeStudent("stu_2", "Diya", "3B"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Someone Else", "9876543210", "old-email@example.com")));
        MockMultipartFile file = csv(
                "Rajesh Joshi,9876543210,new-email@example.com,stu_1,Father",
                "Rajesh Joshi,9876543210,new-email@example.com,stu_2,Father"
        );

        ConfirmRequest confirmRequest = new ConfirmRequest(Map.of(
                2, new RowResolution(RowAction.CREATE_NEW, null),
                3, new RowResolution(RowAction.CREATE_NEW, null)));
        ConfirmResponse result = service.confirm(file, confirmRequest, request);

        assertThat(result.parentsCreated()).isEqualTo(1);
        assertThat(result.relationshipsCreated()).isEqualTo(2);
        verify(idGeneratorService, org.mockito.Mockito.times(1)).generateParentId();
        assertThat(result.created()).extracting(ConfirmedRow::parentId).containsOnly(result.created().get(0).parentId());
    }

    @Test
    void conflictRow_withNoResolutionSupplied_defaultsToSkip_neverAutoLinked() {
        // The API-level default when the caller (e.g. a stale/direct request) supplies no
        // resolution at all for an ambiguous row — must never silently link to the matched
        // parent just because a match was found.
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "old-email@example.com")));
        MockMultipartFile file = csv("Rajesh Joshi,9876543210,new-email@example.com,stu_1,Father");

        ConfirmResponse result = service.confirm(file, new ConfirmRequest(null), request);

        assertThat(result.parentsCreated()).isZero();
        assertThat(result.relationshipsCreated()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(parentRepository, org.mockito.Mockito.never()).save(any());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    // ─── Siblings: one Parent, multiple relationships ──────────────────────

    @Test
    void siblingRows_shareIdentity_createExactlyOneParentAndTwoRelationships() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        givenStudent("stu_2", activeStudent("stu_2", "Diya", "3B"));
        MockMultipartFile file = csv(
                "Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father",
                "Rajesh Joshi,9876543210,rajesh@example.com,stu_2,Father"
        );

        ConfirmResponse result = service.confirm(file, new ConfirmRequest(Map.of()), request);

        assertThat(result.parentsCreated()).isEqualTo(1);
        assertThat(result.relationshipsCreated()).isEqualTo(2);
        verify(idGeneratorService, org.mockito.Mockito.times(1)).generateParentId();
        // Both children end up under the SAME generated parentId.
        assertThat(result.created()).extracting(ConfirmedRow::parentId).containsOnly(result.created().get(0).parentId());
    }

    // ─── Duplicate relationship rejection ──────────────────────────────────

    @Test
    void rowMatchingAnExistingParentAlreadyLinkedToThatStudent_isSkippedAsAlreadyLinked() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "rajesh@example.com")));
        when(relationshipRepository.findBySchoolIdAndParentIdAndStudentId(SCHOOL_ID, "par_26010001", "stu_1"))
                .thenReturn(Optional.of(new ParentStudentRelationship()));

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father"));

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.ALREADY_LINKED);
        assertThat(preview.duplicateCount()).isEqualTo(1);
    }

    @Test
    void duplicateRowWithinTheSameFile_isSkipped_onlyOneRelationshipCreated() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        MockMultipartFile file = csv(
                "Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father",
                "Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father"
        );

        ConfirmResponse result = service.confirm(file, new ConfirmRequest(Map.of()), request);

        assertThat(result.relationshipsCreated()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    // ─── Invalid / cross-school student ─────────────────────────────────────

    @Test
    void invalidStudentId_isRejectedAndNeverCreatesAnAccount() {
        givenStudent("stu_missing", null);

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_missing,Father"));

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.INVALID_STUDENT_ID);
        assertThat(preview.invalidCount()).isEqualTo(1);
    }

    @Test
    void studentBelongingToAnotherSchool_isIndistinguishableFromInvalid_neverLeaksExistence() {
        // findByStudentIdAndSchoolId is scoped by schoolId — a real student in a DIFFERENT
        // school simply never resolves here, exactly like a nonexistent id. No separate
        // "belongs to another school" signal is ever surfaced to the admin.
        when(studentRepository.findByStudentIdAndSchoolId("stu_other_school", SCHOOL_ID)).thenReturn(Optional.empty());

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_other_school,Father"));

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.INVALID_STUDENT_ID);
    }

    @Test
    void exitedStudent_cannotBeLinked() {
        Student exited = activeStudent("stu_1", "Aarav", "5A");
        exited.setStatus(StudentStatus.GRADUATED);
        givenStudent("stu_1", exited);

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father"));

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.STUDENT_EXITED);
    }

    // ─── Existing parent linked to a NEW (different) child ─────────────────

    @Test
    void existingParentMatch_linkedToANewChild_addsOnlyARelationship_noNewParentAccount() {
        givenStudent("stu_2", activeStudent("stu_2", "Diya", "3B"));
        when(parentRepository.findBySchoolIdOrderByNameAsc(SCHOOL_ID))
                .thenReturn(List.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "rajesh@example.com")));
        when(parentRepository.findByParentIdAndSchoolId("par_26010001", SCHOOL_ID))
                .thenReturn(Optional.of(existingParent("par_26010001", "Rajesh Joshi", "9876543210", "rajesh@example.com")));
        MockMultipartFile file = csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_2,Father");

        ConfirmResponse result = service.confirm(file, new ConfirmRequest(Map.of()), request);

        assertThat(result.parentsCreated()).isZero();
        assertThat(result.relationshipsCreated()).isEqualTo(1);
        assertThat(result.created().get(0).parentId()).isEqualTo("par_26010001");
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    // ─── Standard Access defaults + Pay Fees/View Fees dependency ──────────

    @Test
    void newRelationship_getsStandardAccessDefaults_payFeesAlwaysPairedWithViewFees() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        MockMultipartFile file = csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father");

        service.confirm(file, new ConfirmRequest(Map.of()), request);

        ArgumentCaptor<ParentStudentRelationship> captor = ArgumentCaptor.forClass(ParentStudentRelationship.class);
        verify(relationshipRepository).save(captor.capture());
        ParentStudentRelationship saved = captor.getValue();
        assertThat(saved.isCanViewAttendance()).isTrue();
        assertThat(saved.isCanViewFees()).isTrue();
        assertThat(saved.isCanPayFees()).isTrue();
        assertThat(saved.isCanViewResults()).isTrue();
        assertThat(saved.isCanViewTimetable()).isTrue();
        assertThat(saved.isCanManageLeave()).isTrue();
        // The invariant itself: pay fees is never true without view fees also being true.
        assertThat(saved.isCanPayFees()).isTrue();
        assertThat(saved.isCanViewFees()).isTrue();
    }

    @Test
    void newParentAccount_getsAGeneratedIdAndSendsTheWelcomeLink_neverAnAdminSuppliedPassword() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));
        MockMultipartFile file = csv("Rajesh Joshi,9876543210,rajesh@example.com,stu_1,Father");

        service.confirm(file, new ConfirmRequest(Map.of()), request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUserId()).startsWith("par_26");
        assertThat(userCaptor.getValue().isMustChangePassword()).isTrue();
        verify(passwordResetService).sendParentWelcomeLink(any(), org.mockito.ArgumentMatchers.eq("Rajesh Joshi"), anyString());
    }

    @Test
    void rowMissingRequiredField_isRejected() {
        MockMultipartFile file = csv(",9876543210,rajesh@example.com,stu_1,Father"); // blank parent name

        PreviewResponse preview = service.preview(file);

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.MISSING_REQUIRED_FIELD);
    }

    @Test
    void newParentRowWithBlankEmail_isRejected_emailNeededForAccountSetupLink() {
        givenStudent("stu_1", activeStudent("stu_1", "Aarav", "5A"));

        PreviewResponse preview = service.preview(csv("Rajesh Joshi,9876543210,,stu_1,Father"));

        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.MISSING_REQUIRED_FIELD);
        assertThat(preview.rows().get(0).message()).contains("Email is required");
    }
}
