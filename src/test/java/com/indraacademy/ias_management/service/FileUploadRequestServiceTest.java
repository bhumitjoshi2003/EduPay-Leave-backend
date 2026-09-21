package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.UploadCompleteRequest;
import com.indraacademy.ias_management.dto.UploadRequestRequest;
import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.entity.Event;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.UploadIntent;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.EventRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UploadIntentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileUploadRequestServiceTest {

    private static final Long SCHOOL_ID = 1L;
    private static final String TEACHER_ID = "T1";

    @Mock private ObjectStorageService objectStorageService;
    @Mock private UploadIntentRepository uploadIntentRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private AdminRepository adminRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SecurityUtil securityUtil;

    private FileUploadRequestService service;

    @BeforeEach
    void setUp() {
        service = new FileUploadRequestService();
        ReflectionTestUtils.setField(service, "objectStorageService", objectStorageService);
        ReflectionTestUtils.setField(service, "uploadIntentRepository", uploadIntentRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "adminRepository", adminRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "eventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "presignExpirySeconds", 600L);

        lenient().when(uploadIntentRepository.save(any(UploadIntent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private UploadRequestRequest requestFor(String entityId, String contentType, long size) {
        UploadRequestRequest r = new UploadRequestRequest();
        r.setPurpose("TEACHER_PROFILE_PHOTO");
        r.setEntityId(entityId);
        r.setFileName("photo.jpg");
        r.setContentType(contentType);
        r.setSize(size);
        return r;
    }

    private Teacher teacher(String id) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setSchoolId(SCHOOL_ID);
        return t;
    }

    // ─── createUploadRequest ──────────────────────────────────────────────────────────────

    @Test
    void authorizedAdmin_validRequest_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher(TEACHER_ID)));
        when(objectStorageService.buildObjectKey(eq(SCHOOL_ID), eq("teachers"), eq(TEACHER_ID), eq("profile"), eq("jpg")))
                .thenReturn("schools/1/teachers/T1/profile/uuid.jpg");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload(
                        "schools/1/teachers/T1/profile/uuid.jpg", "https://storage.example/put-url",
                        java.time.Instant.now().plusSeconds(600), java.util.Map.of("Content-Type", "image/jpeg")));

        var response = service.createUploadRequest(requestFor(TEACHER_ID, "image/jpeg", 1_000_000));

        assertThat(response.objectKey()).isEqualTo("schools/1/teachers/T1/profile/uuid.jpg");
        assertThat(response.uploadUrl()).isEqualTo("https://storage.example/put-url");
        ArgumentCaptor<UploadIntent> captor = ArgumentCaptor.forClass(UploadIntent.class);
        verify(uploadIntentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UploadIntent.STATUS_PENDING);
        assertThat(captor.getValue().getSchoolId()).isEqualTo(SCHOOL_ID);
        assertThat(captor.getValue().getRequestedByUserId()).isEqualTo("admin1");
    }

    @Test
    void nonAdmin_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("TEACHER");

        assertThatThrownBy(() -> service.createUploadRequest(requestFor(TEACHER_ID, "image/jpeg", 1000)))
                .isInstanceOf(AccessDeniedException.class);
        verify(uploadIntentRepository, never()).save(any());
    }

    @Test
    void noSchoolContext_denied() {
        when(securityUtil.getSchoolId()).thenReturn(null);

        assertThatThrownBy(() -> service.createUploadRequest(requestFor(TEACHER_ID, "image/jpeg", 1000)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void crossSchoolTeacher_denied() {
        // Teacher exists, but NOT in the caller's school — the repository lookup scoped by
        // schoolId correctly returns empty, and this must surface as "not found", never leak
        // the teacher's existence in another school.
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createUploadRequest(requestFor(TEACHER_ID, "image/jpeg", 1000)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void invalidEntity_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherRepository.findByTeacherIdAndSchoolId("NOPE", SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createUploadRequest(requestFor("NOPE", "image/jpeg", 1000)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void unsupportedMimeType_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher(TEACHER_ID)));

        assertThatThrownBy(() -> service.createUploadRequest(requestFor(TEACHER_ID, "application/pdf", 1000)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(objectStorageService, never()).createPresignedUploadUrl(anyString(), anyString());
    }

    @Test
    void unsupportedMimeType_executableDisguisedAsImage_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher(TEACHER_ID)));

        assertThatThrownBy(() -> service.createUploadRequest(requestFor(TEACHER_ID, "application/x-msdownload", 1000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizeDeclaredSize_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher(TEACHER_ID)));

        assertThatThrownBy(() -> service.createUploadRequest(requestFor(TEACHER_ID, "image/jpeg", 6L * 1024 * 1024)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedPurpose_denied() {
        UploadRequestRequest req = requestFor(TEACHER_ID, "image/jpeg", 1000);
        req.setPurpose("NOT_A_REAL_PURPOSE"); // never a valid UploadPurpose constant

        assertThatThrownBy(() -> service.createUploadRequest(req)).isInstanceOf(IllegalArgumentException.class);
    }

    // ─── completeUpload ───────────────────────────────────────────────────────────────────

    private UploadIntent pendingIntent(String objectKey, LocalDateTime expiresAt) {
        UploadIntent intent = new UploadIntent();
        intent.setId(99L);
        intent.setSchoolId(SCHOOL_ID);
        intent.setRequestedByUserId("admin1");
        intent.setPurpose("TEACHER_PROFILE_PHOTO");
        intent.setEntityId(TEACHER_ID);
        intent.setObjectKey(objectKey);
        intent.setExpectedContentType("image/jpeg");
        intent.setExpectedSize(1000L);
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(expiresAt);
        return intent;
    }

    private UploadCompleteRequest completeRequestFor(String objectKey) {
        UploadCompleteRequest r = new UploadCompleteRequest();
        r.setObjectKey(objectKey);
        r.setPurpose("TEACHER_PROFILE_PHOTO");
        r.setEntityId(TEACHER_ID);
        return r;
    }

    @Test
    void completion_unknownObjectKey_rejected() throws Exception {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(uploadIntentRepository.findByObjectKey("bogus-key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeUpload(completeRequestFor("bogus-key")))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void completion_wrongSchool_rejected() {
        UploadIntent intent = pendingIntent("schools/2/teachers/T1/profile/x.jpg", LocalDateTime.now().plusMinutes(5));
        intent.setSchoolId(2L); // different school than the caller
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));

        assertThatThrownBy(() -> service.completeUpload(completeRequestFor(intent.getObjectKey())))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void completion_wrongEntityId_rejected() {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/x.jpg", LocalDateTime.now().plusMinutes(5));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));

        UploadCompleteRequest req = completeRequestFor(intent.getObjectKey());
        req.setEntityId("T2"); // does not match the intent's entityId

        assertThatThrownBy(() -> service.completeUpload(req)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void completion_alreadyCompleted_rejected() {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/x.jpg", LocalDateTime.now().plusMinutes(5));
        intent.setStatus(UploadIntent.STATUS_COMPLETED);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));

        assertThatThrownBy(() -> service.completeUpload(completeRequestFor(intent.getObjectKey())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completion_expiredIntent_rejected() {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/x.jpg", LocalDateTime.now().minusMinutes(1));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));

        assertThatThrownBy(() -> service.completeUpload(completeRequestFor(intent.getObjectKey())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completion_headMissingObject_rejected() {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/x.jpg", LocalDateTime.now().plusMinutes(5));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher(TEACHER_ID)));
        when(objectStorageService.headObject(intent.getObjectKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeUpload(completeRequestFor(intent.getObjectKey())))
                .isInstanceOf(IllegalStateException.class);
        verify(teacherRepository, never()).save(any());
    }

    @Test
    void completion_actualSizeExceedsLimit_rejected() {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/x.jpg", LocalDateTime.now().plusMinutes(5));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher(TEACHER_ID)));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(50L * 1024 * 1024, "image/jpeg")));

        assertThatThrownBy(() -> service.completeUpload(completeRequestFor(intent.getObjectKey())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completion_actualContentTypeNotAllowed_rejected() {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/x.jpg", LocalDateTime.now().plusMinutes(5));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher(TEACHER_ID)));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "application/pdf")));

        assertThatThrownBy(() -> service.completeUpload(completeRequestFor(intent.getObjectKey())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completion_success_persistsReferenceAndMarksIntentCompleted() throws Exception {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/new.jpg", LocalDateTime.now().plusMinutes(5));
        Teacher teacher = teacher(TEACHER_ID);
        teacher.setPhotoUrl(null); // first-ever photo — nothing to clean up
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/jpeg")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey()))
                .thenReturn(new URL("https://storage.example/get-url"));

        var response = service.completeUpload(completeRequestFor(intent.getObjectKey()));

        assertThat(teacher.getPhotoUrl()).isEqualTo(intent.getObjectKey());
        assertThat(response.objectKey()).isEqualTo(intent.getObjectKey());
        assertThat(response.displayUrl()).isEqualTo("https://storage.example/get-url");
        verify(teacherRepository).save(teacher);
        ArgumentCaptor<UploadIntent> captor = ArgumentCaptor.forClass(UploadIntent.class);
        verify(uploadIntentRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UploadIntent.STATUS_COMPLETED);
        verify(objectStorageService, never()).deleteObjectQuietly(anyString()); // nothing to clean up
    }

    @Test
    void completion_replacement_deletesOldObjectOnlyAfterNewReferenceIsPersisted() throws Exception {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/new.jpg", LocalDateTime.now().plusMinutes(5));
        Teacher teacher = teacher(TEACHER_ID);
        teacher.setPhotoUrl("schools/1/teachers/T1/profile/old.jpg"); // previous object-storage photo
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/jpeg")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey()))
                .thenReturn(new URL("https://storage.example/get-url"));

        service.completeUpload(completeRequestFor(intent.getObjectKey()));

        // Old object deleted only as cleanup, and the new reference was already saved by then.
        assertThat(teacher.getPhotoUrl()).isEqualTo(intent.getObjectKey());
        verify(objectStorageService).deleteObjectQuietly("schools/1/teachers/T1/profile/old.jpg");
    }

    @Test
    void completion_replacement_legacyLocalDiskPhoto_neverPassedToObjectStorageDelete() throws Exception {
        UploadIntent intent = pendingIntent("schools/1/teachers/T1/profile/new.jpg", LocalDateTime.now().plusMinutes(5));
        Teacher teacher = teacher(TEACHER_ID);
        teacher.setPhotoUrl("/uploads/teacher-photos/T1.jpg"); // legacy local-disk photo
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(teacherRepository.findByTeacherIdAndSchoolId(TEACHER_ID, SCHOOL_ID)).thenReturn(Optional.of(teacher));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/jpeg")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey()))
                .thenReturn(new URL("https://storage.example/get-url"));

        service.completeUpload(completeRequestFor(intent.getObjectKey()));

        // The legacy local-disk value is never touched by object-storage delete — this backend
        // no longer serves it at all (Phase 3 removed PersonalMediaController and the local
        // write paths), but completeUpload must still never mistake it for an object-storage
        // key it should try to clean up.
        verify(objectStorageService, never()).deleteObjectQuietly(anyString());
    }

    // ─── Phase 2: STUDENT_PROFILE_PHOTO ────────────────────────────────────────────────────

    private UploadRequestRequest requestForPurpose(String purpose, String entityId, String contentType, long size) {
        UploadRequestRequest r = new UploadRequestRequest();
        r.setPurpose(purpose);
        r.setEntityId(entityId);
        r.setFileName("file.jpg");
        r.setContentType(contentType);
        r.setSize(size);
        return r;
    }

    private Student student(String id) {
        Student s = new Student();
        s.setStudentId(id);
        s.setSchoolId(SCHOOL_ID);
        return s;
    }

    @Test
    void studentPhoto_authorizedAdmin_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student("S1")));
        when(objectStorageService.buildObjectKey(eq(SCHOOL_ID), eq("students"), eq("S1"), eq("profile"), eq("jpg")))
                .thenReturn("schools/1/students/S1/profile/uuid.jpg");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload("schools/1/students/S1/profile/uuid.jpg",
                        "https://storage.example/put-url", java.time.Instant.now().plusSeconds(600), java.util.Map.of()));

        var response = service.createUploadRequest(requestForPurpose("STUDENT_PROFILE_PHOTO", "S1", "image/jpeg", 1000));

        assertThat(response.objectKey()).isEqualTo("schools/1/students/S1/profile/uuid.jpg");
    }

    @Test
    void studentPhoto_nonAdmin_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("TEACHER");

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("STUDENT_PROFILE_PHOTO", "S1", "image/jpeg", 1000)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void studentPhoto_crossSchoolStudent_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("STUDENT_PROFILE_PHOTO", "S1", "image/jpeg", 1000)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void studentPhoto_completion_persistsToStudentPhotoUrl() throws Exception {
        UploadIntent intent = new UploadIntent();
        intent.setSchoolId(SCHOOL_ID);
        intent.setPurpose("STUDENT_PROFILE_PHOTO");
        intent.setEntityId("S1");
        intent.setObjectKey("schools/1/students/S1/profile/new.jpg");
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        Student student = student("S1");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/jpeg")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey())).thenReturn(new URL("https://storage.example/get-url"));

        UploadCompleteRequest req = new UploadCompleteRequest();
        req.setObjectKey(intent.getObjectKey());
        req.setPurpose("STUDENT_PROFILE_PHOTO");
        req.setEntityId("S1");
        service.completeUpload(req);

        assertThat(student.getPhotoUrl()).isEqualTo(intent.getObjectKey());
        verify(studentRepository).save(student);
    }

    // ─── Phase 2: ADMIN_PROFILE_PHOTO ───────────────────────────────────────────────────────

    private Admin admin(String id, Long schoolId) {
        Admin a = new Admin();
        a.setAdminId(id);
        a.setSchoolId(schoolId);
        return a;
    }

    @Test
    void adminPhoto_adminUploadsOwnPhoto_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("A1");
        when(adminRepository.findByAdminIdAndSchoolId("A1", SCHOOL_ID)).thenReturn(Optional.of(admin("A1", SCHOOL_ID)));
        when(objectStorageService.buildObjectKey(eq(SCHOOL_ID), eq("admins"), eq("A1"), eq("profile"), eq("jpg")))
                .thenReturn("schools/1/admins/A1/profile/uuid.jpg");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload("schools/1/admins/A1/profile/uuid.jpg",
                        "https://storage.example/put-url", java.time.Instant.now().plusSeconds(600), java.util.Map.of()));

        var response = service.createUploadRequest(requestForPurpose("ADMIN_PROFILE_PHOTO", "A1", "image/jpeg", 1000));

        assertThat(response.objectKey()).isEqualTo("schools/1/admins/A1/profile/uuid.jpg");
    }

    @Test
    void adminPhoto_adminUploadsAnotherAdminsPhoto_denied() {
        // Same-school ADMIN targeting a DIFFERENT admin's id — must never be allowed, mirroring
        // AdminController.uploadAdminPhoto's existing "admins can only upload their own photo" rule.
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("A1");

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("ADMIN_PROFILE_PHOTO", "A2", "image/jpeg", 1000)))
                .isInstanceOf(AccessDeniedException.class);
        verify(adminRepository, never()).save(any());
    }

    @Test
    void adminPhoto_superAdminUploadsAnyAdminsPhoto_succeeds_evenCrossSchool() {
        // SUPER_ADMIN's own session carries no schoolId — the TARGET admin's schoolId is used
        // instead (see FileUploadRequestService.resolveSchoolId), and cross-school targeting is
        // allowed by design for this one role, mirroring AdminService.findAdminByIdForCurrentUser.
        when(securityUtil.getRole()).thenReturn("SUPER_ADMIN");
        when(securityUtil.getUsername()).thenReturn("super1");
        Long otherSchoolId = 2L;
        when(adminRepository.findById("A9")).thenReturn(Optional.of(admin("A9", otherSchoolId)));
        when(objectStorageService.buildObjectKey(eq(otherSchoolId), eq("admins"), eq("A9"), eq("profile"), eq("jpg")))
                .thenReturn("schools/2/admins/A9/profile/uuid.jpg");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload("schools/2/admins/A9/profile/uuid.jpg",
                        "https://storage.example/put-url", java.time.Instant.now().plusSeconds(600), java.util.Map.of()));

        var response = service.createUploadRequest(requestForPurpose("ADMIN_PROFILE_PHOTO", "A9", "image/jpeg", 1000));

        assertThat(response.objectKey()).isEqualTo("schools/2/admins/A9/profile/uuid.jpg");
    }

    @Test
    void adminPhoto_teacherRole_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("TEACHER");

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("ADMIN_PROFILE_PHOTO", "A1", "image/jpeg", 1000)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminPhoto_completion_persistsToAdminPhotoUrl() throws Exception {
        UploadIntent intent = new UploadIntent();
        intent.setSchoolId(SCHOOL_ID);
        intent.setPurpose("ADMIN_PROFILE_PHOTO");
        intent.setEntityId("A1");
        intent.setObjectKey("schools/1/admins/A1/profile/new.jpg");
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        Admin admin = admin("A1", SCHOOL_ID);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("A1");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(adminRepository.findByAdminIdAndSchoolId("A1", SCHOOL_ID)).thenReturn(Optional.of(admin));
        when(adminRepository.findById("A1")).thenReturn(Optional.of(admin));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/jpeg")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey())).thenReturn(new URL("https://storage.example/get-url"));

        UploadCompleteRequest req = new UploadCompleteRequest();
        req.setObjectKey(intent.getObjectKey());
        req.setPurpose("ADMIN_PROFILE_PHOTO");
        req.setEntityId("A1");
        service.completeUpload(req);

        assertThat(admin.getPhotoUrl()).isEqualTo(intent.getObjectKey());
        verify(adminRepository).save(admin);
    }

    // ─── Phase 2: SCHOOL_LOGO / REPORT_CARD_HEADER_IMAGE ────────────────────────────────────

    private School school() {
        School s = new School();
        s.setId(SCHOOL_ID);
        return s;
    }

    @Test
    void schoolLogo_adminUploads_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(objectStorageService.buildSchoolLevelObjectKey(SCHOOL_ID, "logo", "png"))
                .thenReturn("schools/1/school/logo/uuid.png");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload("schools/1/school/logo/uuid.png",
                        "https://storage.example/put-url", java.time.Instant.now().plusSeconds(600), java.util.Map.of()));

        var response = service.createUploadRequest(requestForPurpose("SCHOOL_LOGO", "self", "image/png", 1000));

        assertThat(response.objectKey()).isEqualTo("schools/1/school/logo/uuid.png");
    }

    @Test
    void schoolLogo_nonAdmin_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("TEACHER");

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("SCHOOL_LOGO", "self", "image/png", 1000)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void schoolLogo_completion_persistsAndDeletesOldObjectStorageLogo() throws Exception {
        UploadIntent intent = new UploadIntent();
        intent.setSchoolId(SCHOOL_ID);
        intent.setPurpose("SCHOOL_LOGO");
        intent.setEntityId("self");
        intent.setObjectKey("schools/1/school/logo/new.png");
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        School school = school();
        school.setLogoUrl("schools/1/school/logo/old.png");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/png")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey())).thenReturn(new URL("https://storage.example/get-url"));

        UploadCompleteRequest req = new UploadCompleteRequest();
        req.setObjectKey(intent.getObjectKey());
        req.setPurpose("SCHOOL_LOGO");
        req.setEntityId("self");
        service.completeUpload(req);

        assertThat(school.getLogoUrl()).isEqualTo(intent.getObjectKey());
        verify(schoolRepository).save(school);
        verify(objectStorageService).deleteObjectQuietly("schools/1/school/logo/old.png");
    }

    @Test
    void reportCardHeader_adminUploads_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(objectStorageService.buildSchoolLevelObjectKey(SCHOOL_ID, "report-card-header", "png"))
                .thenReturn("schools/1/school/report-card-header/uuid.png");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload("schools/1/school/report-card-header/uuid.png",
                        "https://storage.example/put-url", java.time.Instant.now().plusSeconds(600), java.util.Map.of()));

        var response = service.createUploadRequest(requestForPurpose("REPORT_CARD_HEADER_IMAGE", "self", "image/png", 1000));

        assertThat(response.objectKey()).isEqualTo("schools/1/school/report-card-header/uuid.png");
    }

    @Test
    void reportCardHeader_nonAdmin_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("TEACHER");

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("REPORT_CARD_HEADER_IMAGE", "self", "image/png", 1000)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reportCardHeader_completion_persistsToSchoolField() throws Exception {
        UploadIntent intent = new UploadIntent();
        intent.setSchoolId(SCHOOL_ID);
        intent.setPurpose("REPORT_CARD_HEADER_IMAGE");
        intent.setEntityId("self");
        intent.setObjectKey("schools/1/school/report-card-header/new.png");
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        School school = school();
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/png")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey())).thenReturn(new URL("https://storage.example/get-url"));

        UploadCompleteRequest req = new UploadCompleteRequest();
        req.setObjectKey(intent.getObjectKey());
        req.setPurpose("REPORT_CARD_HEADER_IMAGE");
        req.setEntityId("self");
        service.completeUpload(req);

        assertThat(school.getReportCardHeaderImageUrl()).isEqualTo(intent.getObjectKey());
        verify(schoolRepository).save(school);
    }

    // ─── Phase 2: EVENT_IMAGE ────────────────────────────────────────────────────────────────

    private Event event(Long id) {
        Event e = new Event();
        e.setId(id);
        e.setSchoolId(SCHOOL_ID);
        return e;
    }

    @Test
    void eventImage_adminUploads_newEventSentinel_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(objectStorageService.buildObjectKey(eq(SCHOOL_ID), eq("events"), eq("new"), eq("images"), eq("jpg")))
                .thenReturn("schools/1/events/new/images/uuid.jpg");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload("schools/1/events/new/images/uuid.jpg",
                        "https://storage.example/put-url", java.time.Instant.now().plusSeconds(600), java.util.Map.of()));

        var response = service.createUploadRequest(requestForPurpose("EVENT_IMAGE", "new", "image/jpeg", 1000));

        assertThat(response.objectKey()).isEqualTo("schools/1/events/new/images/uuid.jpg");
        verifyNoInteractions(eventRepository);
    }

    @Test
    void eventImage_adminUploads_existingEvent_succeeds() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(eventRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.of(event(5L)));
        when(objectStorageService.buildObjectKey(eq(SCHOOL_ID), eq("events"), eq("5"), eq("images"), eq("jpg")))
                .thenReturn("schools/1/events/5/images/uuid.jpg");
        when(objectStorageService.createPresignedUploadUrl(anyString(), anyString()))
                .thenReturn(new ObjectStorageService.PresignedUpload("schools/1/events/5/images/uuid.jpg",
                        "https://storage.example/put-url", java.time.Instant.now().plusSeconds(600), java.util.Map.of()));

        var response = service.createUploadRequest(requestForPurpose("EVENT_IMAGE", "5", "image/jpeg", 1000));

        assertThat(response.objectKey()).isEqualTo("schools/1/events/5/images/uuid.jpg");
    }

    @Test
    void eventImage_nonAdmin_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("TEACHER");

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("EVENT_IMAGE", "new", "image/jpeg", 1000)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void eventImage_invalidEventId_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("EVENT_IMAGE", "not-a-number", "image/jpeg", 1000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventImage_crossSchoolEvent_denied() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(eventRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createUploadRequest(requestForPurpose("EVENT_IMAGE", "5", "image/jpeg", 1000)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void eventImage_completion_newEventSentinel_doesNotAttach_butStillCompletes() throws Exception {
        UploadIntent intent = new UploadIntent();
        intent.setSchoolId(SCHOOL_ID);
        intent.setPurpose("EVENT_IMAGE");
        intent.setEntityId("new");
        intent.setObjectKey("schools/1/events/new/images/new.jpg");
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/jpeg")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey())).thenReturn(new URL("https://storage.example/get-url"));

        UploadCompleteRequest req = new UploadCompleteRequest();
        req.setObjectKey(intent.getObjectKey());
        req.setPurpose("EVENT_IMAGE");
        req.setEntityId("new");
        var response = service.completeUpload(req);

        assertThat(response.objectKey()).isEqualTo(intent.getObjectKey());
        verifyNoInteractions(eventRepository);
        verify(objectStorageService, never()).deleteObjectQuietly(anyString());
    }

    @Test
    void eventImage_completion_existingEvent_persistsImageUrlAndDeletesOld() throws Exception {
        UploadIntent intent = new UploadIntent();
        intent.setSchoolId(SCHOOL_ID);
        intent.setPurpose("EVENT_IMAGE");
        intent.setEntityId("5");
        intent.setObjectKey("schools/1/events/5/images/new.jpg");
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        Event event = event(5L);
        event.setImageUrl("schools/1/events/5/images/old.jpg");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(uploadIntentRepository.findByObjectKey(intent.getObjectKey())).thenReturn(Optional.of(intent));
        when(eventRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.of(event));
        when(objectStorageService.headObject(intent.getObjectKey()))
                .thenReturn(Optional.of(new ObjectStorageService.ObjectMetadata(1000L, "image/jpeg")));
        when(objectStorageService.createPresignedDownloadUrl(intent.getObjectKey())).thenReturn(new URL("https://storage.example/get-url"));

        UploadCompleteRequest req = new UploadCompleteRequest();
        req.setObjectKey(intent.getObjectKey());
        req.setPurpose("EVENT_IMAGE");
        req.setEntityId("5");
        service.completeUpload(req);

        assertThat(event.getImageUrl()).isEqualTo(intent.getObjectKey());
        verify(eventRepository).save(event);
        verify(objectStorageService).deleteObjectQuietly("schools/1/events/5/images/old.jpg");
    }
}
