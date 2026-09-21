package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.UploadCompleteRequest;
import com.indraacademy.ias_management.dto.UploadRequestRequest;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.UploadIntent;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileUploadRequestServiceTest {

    private static final Long SCHOOL_ID = 1L;
    private static final String TEACHER_ID = "T1";

    @Mock private ObjectStorageService objectStorageService;
    @Mock private UploadIntentRepository uploadIntentRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SecurityUtil securityUtil;

    private FileUploadRequestService service;

    @BeforeEach
    void setUp() {
        service = new FileUploadRequestService();
        ReflectionTestUtils.setField(service, "objectStorageService", objectStorageService);
        ReflectionTestUtils.setField(service, "uploadIntentRepository", uploadIntentRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
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
        req.setPurpose("STUDENT_PROFILE_PHOTO"); // not yet implemented

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

        // The legacy local file is never touched by object-storage delete — it stays on disk,
        // served as before by PersonalMediaController, until a future migration phase.
        verify(objectStorageService, never()).deleteObjectQuietly(anyString());
    }
}
