package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.UploadCompleteRequest;
import com.indraacademy.ias_management.dto.UploadCompleteResponse;
import com.indraacademy.ias_management.dto.UploadRequestRequest;
import com.indraacademy.ias_management.dto.UploadRequestResponse;
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
 * <p>Phase 1 authorized exactly one purpose ({@link UploadPurpose#TEACHER_PROFILE_PHOTO}). Phase
 * 2 adds the remaining "normal persistent user upload" categories, each preserving its own
 * pre-existing authorization rules (see the per-case comments in {@link #authorizeForPurpose}) —
 * this class only ever widens by adding a new {@code case}, never by loosening an existing one.
 */
@Service
public class FileUploadRequestService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadRequestService.class);

    @Autowired private ObjectStorageService objectStorageService;
    @Autowired private UploadIntentRepository uploadIntentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AdminRepository adminRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SecurityUtil securityUtil;

    @Value("${object-storage.presign-expiry-seconds:600}")
    private long presignExpirySeconds;

    @Transactional
    public UploadRequestResponse createUploadRequest(UploadRequestRequest request) {
        UploadPurpose purpose = parsePurpose(request.getPurpose());
        Long schoolId = resolveSchoolId(purpose, request.getEntityId());

        authorizeForPurpose(purpose, schoolId, request.getEntityId());

        if (!purpose.allowsContentType(request.getContentType())) {
            throw new IllegalArgumentException("Content type not allowed for purpose " + purpose + ": " + request.getContentType());
        }
        if (request.getSize() == null || request.getSize() <= 0 || request.getSize() > purpose.getMaxSizeBytes()) {
            throw new IllegalArgumentException("Declared file size is invalid or exceeds the limit for purpose " + purpose);
        }

        String extension = UploadPurpose.extensionFor(request.getContentType());
        String objectKey = purpose.schoolLevel()
                ? objectStorageService.buildSchoolLevelObjectKey(schoolId, purpose.objectKeySegment(), extension)
                : objectStorageService.buildObjectKey(schoolId, purpose.entityType(), request.getEntityId(), purpose.objectKeySegment(), extension);

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
        Long schoolId = resolveSchoolId(purpose, request.getEntityId());

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
     * here, keeping that decision in completeUpload alongside its own logging. Returns null for
     * EVENT_IMAGE's "new event" sentinel, where there is no entity yet to attach to — see its
     * case below. */
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
            case STUDENT_PROFILE_PHOTO -> {
                Student student = studentRepository.findByStudentIdAndSchoolId(entityId, requireSchoolId())
                        .orElseThrow(() -> new NoSuchElementException("Student not found: " + entityId));
                String old = student.getPhotoUrl();
                student.setPhotoUrl(newObjectKey);
                studentRepository.save(student);
                yield old;
            }
            case ADMIN_PROFILE_PHOTO -> {
                // Plain findById (not schoolId-scoped) — authorizeForPurpose has already run
                // (both at request time and again just above in completeUpload) with the correct
                // ADMIN-self-only or SUPER_ADMIN-any-admin rule for this exact entityId, so no
                // further scoping is needed to safely persist here.
                Admin admin = adminRepository.findById(entityId)
                        .orElseThrow(() -> new NoSuchElementException("Admin not found: " + entityId));
                String old = admin.getPhotoUrl();
                admin.setPhotoUrl(newObjectKey);
                adminRepository.save(admin);
                yield old;
            }
            case SCHOOL_LOGO -> {
                School school = schoolRepository.findById(requireSchoolId())
                        .orElseThrow(() -> new NoSuchElementException("School not found."));
                String old = school.getLogoUrl();
                school.setLogoUrl(newObjectKey);
                schoolRepository.save(school);
                yield old;
            }
            case REPORT_CARD_HEADER_IMAGE -> {
                School school = schoolRepository.findById(requireSchoolId())
                        .orElseThrow(() -> new NoSuchElementException("School not found."));
                String old = school.getReportCardHeaderImageUrl();
                school.setReportCardHeaderImageUrl(newObjectKey);
                schoolRepository.save(school);
                yield old;
            }
            case EVENT_IMAGE -> {
                if (UploadPurpose.NEW_EVENT_SENTINEL.equals(entityId)) {
                    // The event doesn't exist yet — nothing to attach to. The frontend includes
                    // this response's objectKey as Event.imageUrl when it subsequently creates
                    // the event, exactly mirroring the legacy uploadEventImage-then-save flow.
                    yield null;
                }
                Event event = eventRepository.findByIdAndSchoolId(parseEventId(entityId), requireSchoolId())
                        .orElseThrow(() -> new NoSuchElementException("Event not found: " + entityId));
                String old = event.getImageUrl();
                event.setImageUrl(newObjectKey);
                eventRepository.save(event);
                yield old;
            }
            case SUPPORT_TICKET_SCREENSHOT -> {
                // The ticket doesn't exist yet either — always the "new" sentinel (see
                // UploadPurpose.SUPPORT_TICKET_SCREENSHOT's javadoc). SupportTicketService
                // persists the objectKey directly onto the ticket at creation time.
                yield null;
            }
        };
    }

    private void authorizeForPurpose(UploadPurpose purpose, Long schoolId, String entityId) {
        switch (purpose) {
            case TEACHER_PROFILE_PHOTO -> {
                // Mirrors TeacherController.uploadTeacherPhoto's existing ADMIN-only restriction.
                if (!Role.ADMIN.equals(securityUtil.getRole())) {
                    throw new AccessDeniedException("Not authorized to upload a teacher profile photo.");
                }
                teacherRepository.findByTeacherIdAndSchoolId(entityId, schoolId)
                        .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + entityId));
            }
            case STUDENT_PROFILE_PHOTO -> {
                // Mirrors StudentController's existing ADMIN-only POST .../{studentId}/photo —
                // the self/parent-child read rules (preserved separately on the read side, see
                // StudentController.resolvePhotoUrlForDisplay) never applied to the WRITE side.
                if (!Role.ADMIN.equals(securityUtil.getRole())) {
                    throw new AccessDeniedException("Not authorized to upload a student profile photo.");
                }
                studentRepository.findByStudentIdAndSchoolId(entityId, schoolId)
                        .orElseThrow(() -> new NoSuchElementException("Student not found: " + entityId));
            }
            case ADMIN_PROFILE_PHOTO -> {
                // Mirrors AdminController.uploadAdminPhoto exactly: an ADMIN may only ever target
                // their own id (schoolId-scoped lookup makes cross-school targeting impossible
                // even if an ADMIN somehow guessed another school's own adminId); SUPER_ADMIN may
                // target any admin in any school (id-only lookup, matching
                // AdminService.findAdminByIdForCurrentUser's existing SUPER_ADMIN branch).
                String role = securityUtil.getRole();
                if (Role.SUPER_ADMIN.equals(role)) {
                    adminRepository.findById(entityId)
                            .orElseThrow(() -> new NoSuchElementException("Admin not found: " + entityId));
                } else if (Role.ADMIN.equals(role)) {
                    if (!entityId.equals(securityUtil.getUsername())) {
                        throw new AccessDeniedException("Admins can only upload their own photo.");
                    }
                    adminRepository.findByAdminIdAndSchoolId(entityId, schoolId)
                            .orElseThrow(() -> new NoSuchElementException("Admin not found: " + entityId));
                } else {
                    throw new AccessDeniedException("Not authorized to upload an admin profile photo.");
                }
            }
            case SCHOOL_LOGO, REPORT_CARD_HEADER_IMAGE -> {
                // Mirrors SchoolController's existing hasRole('ADMIN') on both
                // /api/school/logo and /api/school/report-card-header. schoolId itself is
                // already the trusted, session-derived tenant (see requireSchoolId) — there is
                // exactly one school per schoolId, so no further entity lookup is meaningful.
                if (!Role.ADMIN.equals(securityUtil.getRole())) {
                    throw new AccessDeniedException("Not authorized to upload school branding.");
                }
            }
            case EVENT_IMAGE -> {
                // Mirrors EventController's existing hasAnyRole('ADMIN') on create/update. The
                // "new event" sentinel skips the entity lookup entirely — there is nothing to
                // look up yet, and schoolId is already the trusted, session-derived tenant.
                if (!Role.ADMIN.equals(securityUtil.getRole())) {
                    throw new AccessDeniedException("Not authorized to upload an event image.");
                }
                if (!UploadPurpose.NEW_EVENT_SENTINEL.equals(entityId)) {
                    eventRepository.findByIdAndSchoolId(parseEventId(entityId), schoolId)
                            .orElseThrow(() -> new NoSuchElementException("Event not found: " + entityId));
                }
            }
            case SUPPORT_TICKET_SCREENSHOT -> {
                // Any authenticated user with a school context may attach a screenshot to their
                // own not-yet-created support ticket — the controller's isAuthenticated() plus
                // requireSchoolId() above is the whole check; there is no entity to look up yet.
            }
        }
    }

    private UploadPurpose parsePurpose(String raw) {
        try {
            return UploadPurpose.valueOf(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported upload purpose: " + raw);
        }
    }

    /**
     * Normally the caller's own tenant. The one exception is a SUPER_ADMIN uploading ANOTHER
     * school's admin's photo — SUPER_ADMIN's own session carries no schoolId (cross-tenant by
     * design, see AdminService.findAdminByIdForCurrentUser's identical SUPER_ADMIN branch), so
     * the TARGET admin's own schoolId is used instead. Every object key this service builds is
     * still schoolId-prefixed regardless of which path resolved it, so tenant isolation of the
     * stored object itself is unaffected either way.
     */
    private Long resolveSchoolId(UploadPurpose purpose, String entityId) {
        if (purpose == UploadPurpose.ADMIN_PROFILE_PHOTO && Role.SUPER_ADMIN.equals(securityUtil.getRole())) {
            return adminRepository.findById(entityId)
                    .map(Admin::getSchoolId)
                    .orElseThrow(() -> new NoSuchElementException("Admin not found: " + entityId));
        }
        return requireSchoolId();
    }

    private Long requireSchoolId() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId == null) {
            throw new AccessDeniedException("No school context for the current session.");
        }
        return schoolId;
    }

    private Long parseEventId(String entityId) {
        try {
            return Long.parseLong(entityId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid event id: " + entityId);
        }
    }
}
