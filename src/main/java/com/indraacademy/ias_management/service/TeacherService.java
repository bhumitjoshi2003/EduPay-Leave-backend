package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.LimitType;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.dto.TeacherExitRequest;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.time.LocalDate;

@Service
public class TeacherService {

    private static final Logger log = LoggerFactory.getLogger(TeacherService.class);
    private static final long MAX_PHOTO_SIZE = 10L * 1024 * 1024;

    @Value("${teacher.photo.directory:./uploads/teacher-photos}")
    private String photoDirectory;

    @Autowired
    private TeacherRepository teacherRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdGeneratorService idGeneratorService;

    @Autowired
    private SchoolClassRepository schoolClassRepository;

    @Autowired
    private SectionRepository sectionRepository;

    @Transactional(readOnly = true)
    public Optional<Teacher> getTeacher(String teacherId) {
        if (teacherId == null || teacherId.trim().isEmpty()) {
            log.warn("Attempted to get teacher with null/empty ID.");
            return Optional.empty();
        }
        log.info("Fetching teacher with ID: {}", teacherId);
        try {
            return teacherRepository.findByTeacherIdAndSchoolId(teacherId, securityUtil.getSchoolId());
        } catch (DataAccessException e) {
            log.error("Data access error fetching teacher with ID: {}", teacherId, e);
            throw new RuntimeException("Failed to retrieve teacher data.", e);
        }
    }

    @Transactional(readOnly = true)
    public List<Teacher> getAllTeachers() {
        log.info("Fetching all teachers.");
        try {
            return teacherRepository.findBySchoolId(securityUtil.getSchoolId());
        } catch (DataAccessException e) {
            log.error("Data access error fetching all teachers.", e);
            throw new RuntimeException("Failed to retrieve list of teachers.", e);
        }
    }

    public Teacher updateTeacher(Teacher teacher, HttpServletRequest request) {

        if (teacher == null || teacher.getTeacherId() == null || teacher.getTeacherId().trim().isEmpty()) {
            log.error("Attempted to update teacher with null or empty Teacher object/ID.");
            throw new IllegalArgumentException("Teacher object and ID must be provided for update.");
        }

        log.info("Updating teacher with ID: {}", teacher.getTeacherId());

        try {
            Long schoolId = securityUtil.getSchoolId();
            validateAndNormalizeClassResponsibility(teacher, schoolId);
            Optional<Teacher> existingTeacher = teacherRepository.findByTeacherIdAndSchoolId(teacher.getTeacherId(), schoolId);

            String oldValue = null;
            if(existingTeacher.isPresent()){
                oldValue = objectMapper.writeValueAsString(existingTeacher.get());
                // Lifecycle fields are changed only through the audited exit/reactivate endpoints.
                teacher.setStatus(existingTeacher.get().getStatus());
                teacher.setLeavingDate(existingTeacher.get().getLeavingDate());
                teacher.setReasonForLeaving(existingTeacher.get().getReasonForLeaving());
                teacher.setExitRemarks(existingTeacher.get().getExitRemarks());
            }

            teacher.setSchoolId(schoolId);
            // teacher here is the request-body parameter, not existingTeacher fetched
            // above — see Teacher.markAsExisting()'s Javadoc for why this is required.
            teacher.markAsExisting();
            Teacher savedTeacher = teacherRepository.save(teacher);

            auditService.logUpdate(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_TEACHER",
                    "Teacher",
                    teacher.getTeacherId(),
                    oldValue,
                    objectMapper.writeValueAsString(savedTeacher),
                    request.getRemoteAddr()
            );

            return savedTeacher;

        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Error updating teacher with ID: {}", teacher.getTeacherId(), e);
            throw new RuntimeException("Failed to update teacher.", e);
        }
    }

    public Teacher addTeacher(Teacher teacher, HttpServletRequest request) {
        if (teacher == null) {
            log.error("Attempted to add a null Teacher object.");
            throw new IllegalArgumentException("Teacher object must be provided.");
        }
        // Edunexify generates the Employee ID for every new account going forward — same
        // enforcement point as StudentService.addStudent(); see that method's comment for
        // the full rationale. Existing teachers (including legacy SWEEDU-era IDs) are never
        // touched by this — this only fires when teacherId arrives blank, i.e. a genuinely
        // new account.
        if (teacher.getTeacherId() == null || teacher.getTeacherId().trim().isEmpty()) {
            teacher.setTeacherId(idGeneratorService.generateTeacherId());
        }
        if (teacher.getDob() == null) {
            log.warn("Attempted to add teacher {} without a date of birth.", teacher.getTeacherId());
            throw new IllegalArgumentException("Date of birth is required because it is used as the initial password.");
        }
        if (teacher.getJoiningDate() == null) {
            log.warn("Attempted to add teacher {} without a joining date.", teacher.getTeacherId());
            throw new IllegalArgumentException("Joining date is required.");
        }
        log.info("Attempting to add new teacher with ID: {}", teacher.getTeacherId());

        try {
            Long schoolId = securityUtil.getSchoolId();
            validateAndNormalizeClassResponsibility(teacher, schoolId);
            try {
                entitlementService.checkLimit(schoolId, LimitType.STAFF, 1);
            } catch (IllegalStateException e) {
                log.debug("No entitlement for school {} — skipping staff limit check", schoolId);
            }
            Optional<Teacher> existingTeacher = teacherRepository.findByTeacherIdAndSchoolId(teacher.getTeacherId(), schoolId);
            if (existingTeacher.isPresent()) {
                log.warn("Teacher with ID {} already exists.", teacher.getTeacherId());
                throw new IllegalArgumentException("Teacher with ID " + teacher.getTeacherId() + " already exists.");
            }
            // Teacher IDs are unique across the entire platform, not just within a school —
            // see StudentService.addStudent()'s identical check for the full rationale.
            if (teacherRepository.existsById(teacher.getTeacherId())) {
                log.warn("Teacher ID {} is already registered under a different school.", teacher.getTeacherId());
                throw new IllegalArgumentException("Teacher ID " + teacher.getTeacherId() + " is already in use. Teacher IDs must be unique across the entire platform.");
            }
            teacher.setSchoolId(schoolId);
            teacher.setStatus(TeacherStatus.ACTIVE);
            teacher.setLeavingDate(null);
            teacher.setReasonForLeaving(null);
            teacher.setExitRemarks(null);
            Teacher savedTeacher = teacherRepository.save(teacher);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_TEACHER",
                    "Teacher",
                    savedTeacher.getTeacherId(),
                    null,
                    objectMapper.writeValueAsString(savedTeacher),
                    request.getRemoteAddr()
            );

            log.info("Successfully added new teacher with ID: {}", savedTeacher.getTeacherId());
            return savedTeacher;
        } catch (DataAccessException e) {
            log.error("Data access error while adding teacher with ID: {}", teacher.getTeacherId(), e);
            throw new RuntimeException("Failed to add teacher due to a database issue.", e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public Teacher exitTeacher(String teacherId, TeacherExitRequest request, HttpServletRequest httpRequest) {
        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        if (teacher.getStatus() == TeacherStatus.LEFT) {
            throw new IllegalStateException("Teacher is already marked as left.");
        }
        if (request.getLeavingDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Leaving date cannot be in the future.");
        }

        teacher.setStatus(TeacherStatus.LEFT);
        teacher.setLeavingDate(request.getLeavingDate());
        teacher.setReasonForLeaving(request.getReasonForLeaving().trim());
        teacher.setExitRemarks(request.getExitRemarks());
        teacher.setClassTeacher(null);
        userRepository.findByUserId(teacherId).ifPresent(user -> {
            user.setActive(false);
            user.setRefreshTokenId(null);
            userRepository.save(user);
        });
        Teacher saved = teacherRepository.save(teacher);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "EXIT_TEACHER", "Teacher",
                teacherId, "ACTIVE", "LEFT", httpRequest.getRemoteAddr());
        return saved;
    }

    @Transactional
    public Teacher reactivateTeacher(String teacherId, HttpServletRequest httpRequest) {
        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        if (teacher.getStatus() != TeacherStatus.LEFT) {
            throw new IllegalStateException("Only teachers marked as left can be re-activated.");
        }
        teacher.setStatus(TeacherStatus.ACTIVE);
        teacher.setLeavingDate(null);
        teacher.setReasonForLeaving(null);
        teacher.setExitRemarks(null);
        userRepository.findByUserId(teacherId).ifPresent(user -> {
            user.setActive(true);
            user.setRefreshTokenId(null);
            userRepository.save(user);
        });
        Teacher saved = teacherRepository.save(teacher);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "REACTIVATE_TEACHER", "Teacher",
                teacherId, "LEFT", "ACTIVE", httpRequest.getRemoteAddr());
        return saved;
    }

    /**
     * Validates and normalizes {@code teacher.classTeacher}/{@code classTeacherSectionId}
     * in place before save — the single write-time enforcement point shared by manual
     * create/update ({@link #addTeacher}/{@link #updateTeacher}) and CSV bulk import
     * ({@link TeacherBulkImportService}). Never trusts the caller's section value; always
     * re-derives what's actually required/allowed from the canonical SchoolClass/Section data.
     *
     * <ul>
     *   <li>Blank/null classTeacher → both fields cleared (clearing responsibility clears any
     *       section too).</li>
     *   <li>classTeacher must name an existing class in this school.</li>
     *   <li>If that class has configured (active) sections, a section is REQUIRED, and must be
     *       an active Section belonging to that exact class and school — anything else
     *       (another class's section, another school's section, an inactive section, or no
     *       section at all) is rejected outright.</li>
     *   <li>If that class has no configured sections, any incoming sectionId is rejected (not
     *       silently dropped) rather than trusted — it can only be stale/incorrect input.</li>
     * </ul>
     */
    public void validateAndNormalizeClassResponsibility(Teacher teacher, Long schoolId) {
        String classTeacher = teacher.getClassTeacher();
        if (classTeacher == null || classTeacher.isBlank()) {
            teacher.setClassTeacher(null);
            teacher.setClassTeacherSectionId(null);
            return;
        }

        SchoolClass schoolClass = schoolClassRepository.findBySchoolIdAndName(schoolId, classTeacher)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Class Teacher Of: class '" + classTeacher + "' does not exist."));

        List<Section> activeSections = sectionRepository
                .findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, schoolClass.getId(), true);
        Long sectionId = teacher.getClassTeacherSectionId();

        if (!activeSections.isEmpty()) {
            if (sectionId == null) {
                throw new IllegalArgumentException(
                        "Class '" + classTeacher + "' has sections configured — a Section/Group must be selected.");
            }
            boolean belongsToClass = activeSections.stream().anyMatch(s -> s.getId().equals(sectionId));
            if (!belongsToClass) {
                // Covers all three rejection cases at once: another class's section, another
                // school's section (won't even be in activeSections, which is already
                // schoolId-scoped), or an inactive section (excluded by the active=true query).
                throw new IllegalArgumentException(
                        "The selected section does not belong to class '" + classTeacher + "' in this school, "
                                + "or is inactive.");
            }
        } else if (sectionId != null) {
            throw new IllegalArgumentException(
                    "Class '" + classTeacher + "' has no configured sections — a section cannot be assigned.");
        }
    }

    @Transactional
    public String uploadPhoto(String teacherId, MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed.");
        }
        if (file.getSize() > MAX_PHOTO_SIZE) {
            throw new IllegalArgumentException("File size exceeds the 10 MB limit.");
        }

        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));

        try {
            Path storageDir = Paths.get(photoDirectory).toAbsolutePath().normalize();
            Files.createDirectories(storageDir);

            String fileName = teacherId + ".jpg";
            Path targetLocation = storageDir.resolve(fileName);
            Thumbnails.of(file.getInputStream())
                    .size(400, 400)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.80)
                    .toFile(targetLocation.toFile());

            String relativeUrl = "/uploads/teacher-photos/" + fileName;
            teacher.setPhotoUrl(relativeUrl);
            teacherRepository.save(teacher);

            log.info("Photo uploaded and resized for teacher {}: {}", teacherId, relativeUrl);
            return relativeUrl;
        } catch (IOException e) {
            log.error("Failed to store photo for teacher {}", teacherId, e);
            throw new RuntimeException("Could not store photo for teacher " + teacherId, e);
        }
    }
}
