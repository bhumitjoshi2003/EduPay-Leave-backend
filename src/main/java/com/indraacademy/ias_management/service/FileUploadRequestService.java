package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.UploadCompleteRequest;
import com.indraacademy.ias_management.dto.UploadCompleteResponse;
import com.indraacademy.ias_management.dto.UploadRequestRequest;
import com.indraacademy.ias_management.dto.UploadRequestResponse;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.UploadIntent;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UploadIntentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Orchestrates the two-step direct-to-object-storage upload flow: {@link #createUploadRequest}
 * (issue a presigned PUT, recorded as a PENDING {@link UploadIntent}) and {@link #completeUpload}
 * (verify, then attach the object to its entity). This is the ONLY place that trusts a
 * client-supplied objectKey — and even here, only after matching it against a PENDING intent
 * this backend itself issued to the current school/user for the same purpose and entity, then
 * confirming the object genuinely exists via HEAD. See the Phase 1 report for the full threat
 * model this defends against.
 *
 * <p>Phase 1 authorizes exactly one purpose ({@link UploadPurpose#TEACHER_PROFILE_PHOTO}) —
 * mirrors the existing {@code TeacherController.uploadTeacherPhoto}'s ADMIN-only restriction
 * exactly, so this is strictly additive: the legacy endpoint keeps working unchanged as a
 * rollback path.
 */
@Service
public class FileUploadRequestService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadRequestService.class);

    @Autowired private ObjectStorageService objectStorageService;
    @Autowired private UploadIntentRepository uploadIntentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SecurityUtil securityUtil;

    @Value("${object-storage.presign-expiry-seconds:600}")
    private long presignExpirySeconds;

    @Transactional
    public UploadRequestResponse createUploadRequest(UploadRequestRequest request) {
        UploadPurpose purpose = parsePurpose(request.getPurpose());
        Long schoolId = requireSchoolId();

        authorizeForPurpose(purpose, schoolId, request.getEntityId());

        if (!purpose.allowsContentType(request.getContentType())) {
            throw new IllegalArgumentException("Content type not allowed for purpose " + purpose + ": " + request.getContentType());
        }
        if (request.getSize() == null || request.getSize() <= 0 || request.getSize() > purpose.getMaxSizeBytes()) {
            throw new IllegalArgumentException("Declared file size is invalid or exceeds the limit for purpose " + purpose);
        }

        String extension = UploadPurpose.extensionFor(request.getContentType());
        String objectKey = objectStorageService.buildObjectKey(schoolId, entityTypeFor(purpose), request.getEntityId(), "profile", extension);

        ObjectStorageService.PresignedUpload presigned = objectStorageService.createPresignedUploadUrl(objectKey, request.getContentType());

        UploadIntent intent = new UploadIntent();
        intent.setSchoolId(schoolId);
        intent.setRequestedByUserId(securityUtil.getUsername());
        intent.setPurpose(purpose.name());
        intent.setEntityId(request.getEntityId());
        intent.setObjectKey(objectKey);
        intent.setExpectedContentType(request.getContentType());
        intent.setExpectedSize(request.getSize());
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().plusSeconds(presignExpirySeconds));
        uploadIntentRepository.save(intent);

        log.info("Upload request authorized: purpose={} entityId={} schoolId={} objectKey={}",
                purpose, request.getEntityId(), schoolId, objectKey);

        return new UploadRequestResponse(presigned.objectKey(), presigned.uploadUrl(), presigned.expiresAt(), presigned.requiredHeaders());
    }

    @Transactional
    public UploadCompleteResponse completeUpload(UploadCompleteRequest request) {
        UploadPurpose purpose = parsePurpose(request.getPurpose());
        Long schoolId = requireSchoolId();

        UploadIntent intent = uploadIntentRepository.findByObjectKey(request.getObjectKey())
                .orElseThrow(() -> new NoSuchElementException("No upload was authorized for this object."));

        if (!intent.getSchoolId().equals(schoolId)
                || !intent.getPurpose().equals(purpose.name())
                || !intent.getEntityId().equals(request.getEntityId())) {
            // Deliberately the same exception/message as "not found" — never reveal to a caller
            // that an object key exists but belongs to someone else.
            log.warn("Upload completion rejected: objectKey={} does not match the authorizing intent's school/purpose/entity.",
                    request.getObjectKey());
            throw new NoSuchElementException("No upload was authorized for this object.");
        }
        if (!UploadIntent.STATUS_PENDING.equals(intent.getStatus())) {
            throw new IllegalStateException("This upload has already been completed.");
        }
        if (intent.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("This upload authorization has expired. Please request a new upload URL.");
        }

        // Re-authorize (defense in depth — the entity's own state could have changed since the
        // upload was requested, e.g. the teacher was deactivated in between).
        authorizeForPurpose(purpose, schoolId, request.getEntityId());

        ObjectStorageService.ObjectMetadata metadata = objectStorageService.headObject(intent.getObjectKey())
                .orElseThrow(() -> new IllegalStateException("The uploaded object could not be found in storage. It may not have finished uploading."));
        if (metadata.size() > purpose.getMaxSizeBytes()) {
            throw new IllegalStateException("The uploaded object exceeds the allowed size for this purpose.");
        }
        if (!purpose.allowsContentType(metadata.contentType())) {
            throw new IllegalStateException("The uploaded object's content type is not allowed for this purpose.");
        }

        String oldObjectKey = attachToEntity(purpose, request.getEntityId(), intent.getObjectKey());

        intent.setStatus(UploadIntent.STATUS_COMPLETED);
        intent.setCompletedAt(LocalDateTime.now());
        uploadIntentRepository.save(intent);

        if (oldObjectKey != null && ObjectStorageService.isObjectStorageKey(oldObjectKey) && !oldObjectKey.equals(intent.getObjectKey())) {
            // Only after the new reference is safely persisted above — never delete the old
            // object first (see the Phase 1 replacement-flow report).
            objectStorageService.deleteObjectQuietly(oldObjectKey);
        }

        String displayUrl = objectStorageService.createPresignedDownloadUrl(intent.getObjectKey()).toString();
        log.info("Upload completed: purpose={} entityId={} schoolId={} objectKey={}",
                purpose, request.getEntityId(), schoolId, intent.getObjectKey());
        return new UploadCompleteResponse(intent.getObjectKey(), displayUrl);
    }

    /** Persists the new object key onto the target entity and returns whatever value was there
     * before (for the caller to decide whether it needs cleanup) — never null-checks/deletes
     * here, keeping that decision in completeUpload alongside its own logging. */
    private String attachToEntity(UploadPurpose purpose, String entityId, String newObjectKey) {
        return switch (purpose) {
            case TEACHER_PROFILE_PHOTO -> {
                Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(entityId, requireSchoolId())
                        .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + entityId));
                String old = teacher.getPhotoUrl();
                teacher.setPhotoUrl(newObjectKey);
                teacherRepository.save(teacher);
                yield old;
            }
        };
    }

    private void authorizeForPurpose(UploadPurpose purpose, Long schoolId, String entityId) {
        switch (purpose) {
            case TEACHER_PROFILE_PHOTO -> {
                if (!Role.ADMIN.equals(securityUtil.getRole())) {
                    throw new AccessDeniedException("Not authorized to upload a teacher profile photo.");
                }
                teacherRepository.findByTeacherIdAndSchoolId(entityId, schoolId)
                        .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + entityId));
            }
        }
    }

    private String entityTypeFor(UploadPurpose purpose) {
        return switch (purpose) {
            case TEACHER_PROFILE_PHOTO -> "teachers";
        };
    }

    private UploadPurpose parsePurpose(String raw) {
        try {
            return UploadPurpose.valueOf(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported upload purpose: " + raw);
        }
    }

    private Long requireSchoolId() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId == null) {
            throw new AccessDeniedException("No school context for the current session.");
        }
        return schoolId;
    }
}
