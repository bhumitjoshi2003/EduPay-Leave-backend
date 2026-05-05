package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.RazorpayKeysRequest;
import com.indraacademy.ias_management.dto.SchoolOnboardRequest;
import com.indraacademy.ias_management.dto.SchoolSettingsResponse;
import com.indraacademy.ias_management.dto.SchoolSettingsUpdateRequest;
import com.indraacademy.ias_management.dto.SuperAdminDashboardDto;
import com.indraacademy.ias_management.dto.SuperAdminSchoolUpdateRequest;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SubscriptionPlan;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SchoolService {

    private static final Logger log = LoggerFactory.getLogger(SchoolService.class);

    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private PasswordEncoder passwordEncoder;

    /**
     * Creates a new school and its first ADMIN user account.
     * Only callable by SUPER_ADMIN.
     */
    @Transactional
    public SchoolSettingsResponse onboardSchool(SchoolOnboardRequest req, HttpServletRequest request) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("School name is required.");
        }
        if (req.getSlug() == null || req.getSlug().isBlank()) {
            throw new IllegalArgumentException("School slug is required.");
        }
        if (req.getAdminUserId() == null || req.getAdminUserId().isBlank()) {
            throw new IllegalArgumentException("Admin user ID is required.");
        }
        if (req.getAdminEmail() == null || req.getAdminEmail().isBlank()) {
            throw new IllegalArgumentException("Admin email is required.");
        }
        if (req.getAdminPassword() == null || req.getAdminPassword().isBlank()) {
            throw new IllegalArgumentException("Admin password is required.");
        }
        if (schoolRepository.existsBySlug(req.getSlug())) {
            throw new IllegalArgumentException("A school with slug '" + req.getSlug() + "' already exists.");
        }
        if (userRepository.findByUserId(req.getAdminUserId()).isPresent()) {
            throw new IllegalArgumentException("User ID '" + req.getAdminUserId() + "' is already taken.");
        }

        // 1. Create the school record
        School school = new School();
        school.setName(req.getName());
        school.setSlug(req.getSlug());
        school.setBoardType(req.getBoardType());
        school.setAddress(req.getAddress());
        school.setCity(req.getCity());
        school.setState(req.getState());
        school.setPincode(req.getPincode());
        school.setEmail(req.getEmail());
        school.setPhone(req.getPhone());
        school.setWebsite(req.getWebsite());
        school.setLogoUrl(req.getLogoUrl());
        school.setThemeColor(req.getThemeColor());
        school.setContactPersonName(req.getContactPersonName());
        school.setPlan(req.getPlan() != null ? req.getPlan() : SubscriptionPlan.TRIAL);
        school.setMaxStudents(req.getMaxStudents());
        school.setExpiryDate(req.getExpiryDate());
        school.setActive(true);
        school.setOnboardedBy(securityUtil.getUsername());
        School saved = schoolRepository.save(school);
        log.info("School onboarded: id={}, slug={}", saved.getId(), saved.getSlug());

        // 2. Create the first ADMIN user for this school
        User admin = new User();
        admin.setUserId(req.getAdminUserId());
        admin.setEmail(req.getAdminEmail());
        admin.setRole(Role.ADMIN);
        admin.setPassword(passwordEncoder.encode(req.getAdminPassword()));
        admin.setSchoolId(saved.getId());
        userRepository.save(admin);
        log.info("Admin user created: userId={} for schoolId={}", req.getAdminUserId(), saved.getId());

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "ONBOARD_SCHOOL",
                "School",
                String.valueOf(saved.getId()),
                null,
                "School '" + saved.getName() + "' onboarded with admin '" + req.getAdminUserId() + "'",
                request.getRemoteAddr()
        );

        SchoolSettingsResponse response = SchoolSettingsResponse.from(saved);
        response.setAdminUserId(req.getAdminUserId());
        return response;
    }

    /**
     * Returns settings for the calling user's school.
     */
    public SchoolSettingsResponse getSettings() {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        return SchoolSettingsResponse.from(school);
    }

    /**
     * Updates editable school settings. Subscription fields are not touched here.
     */
    @Transactional
    public SchoolSettingsResponse updateSettings(SchoolSettingsUpdateRequest req, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        String oldValue = "name=" + school.getName();

        if (req.getName() != null && !req.getName().isBlank()) school.setName(req.getName());
        if (req.getBoardType() != null) school.setBoardType(req.getBoardType());
        if (req.getAddress() != null) school.setAddress(req.getAddress());
        if (req.getCity() != null) school.setCity(req.getCity());
        if (req.getState() != null) school.setState(req.getState());
        if (req.getPincode() != null) school.setPincode(req.getPincode());
        if (req.getEmail() != null) school.setEmail(req.getEmail());
        if (req.getPhone() != null) school.setPhone(req.getPhone());
        if (req.getWebsite() != null) school.setWebsite(req.getWebsite());
        if (req.getLogoUrl() != null) school.setLogoUrl(req.getLogoUrl());
        if (req.getThemeColor() != null) school.setThemeColor(req.getThemeColor());
        if (req.getContactPersonName() != null) school.setContactPersonName(req.getContactPersonName());

        School updated = schoolRepository.save(school);
        log.info("School settings updated for schoolId={}", schoolId);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "UPDATE_SCHOOL_SETTINGS",
                "School",
                String.valueOf(schoolId),
                oldValue,
                "name=" + updated.getName(),
                request.getRemoteAddr()
        );

        return SchoolSettingsResponse.from(updated);
    }

    /**
     * Stores the school's own Razorpay key ID and secret.
     * Once set, RazorpayService will use these instead of the global application.properties keys.
     */
    @Transactional
    public void updateRazorpayKeys(RazorpayKeysRequest req, HttpServletRequest request) {
        if (req.getKeyId() == null || req.getKeyId().isBlank()) {
            throw new IllegalArgumentException("Razorpay key ID is required.");
        }
        if (req.getKeySecret() == null || req.getKeySecret().isBlank()) {
            throw new IllegalArgumentException("Razorpay key secret is required.");
        }

        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        school.setRazorpayKeyId(req.getKeyId());
        school.setRazorpayKeySecret(req.getKeySecret());
        schoolRepository.save(school);
        log.info("Razorpay keys updated for schoolId={}", schoolId);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "UPDATE_RAZORPAY_KEYS",
                "School",
                String.valueOf(schoolId),
                null,
                "Razorpay keyId updated (secret redacted)",
                request.getRemoteAddr()
        );
    }

    /**
     * SUPER_ADMIN: updates subscription plan, maxStudents, expiryDate, and active flag for any school.
     */
    @Transactional
    public SchoolSettingsResponse updateSubscription(Long schoolId, com.indraacademy.ias_management.entity.SubscriptionPlan plan,
                                                     Integer maxStudents, java.time.LocalDate expiryDate,
                                                     Boolean active, HttpServletRequest request) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        String oldValue = "plan=" + school.getPlan() + ",active=" + school.isActive();

        if (plan != null) school.setPlan(plan);
        if (maxStudents != null) school.setMaxStudents(maxStudents);
        if (expiryDate != null) school.setExpiryDate(expiryDate);
        if (active != null) school.setActive(active);

        School updated = schoolRepository.save(school);
        log.info("Subscription updated for schoolId={}: plan={}, active={}", schoolId, updated.getPlan(), updated.isActive());

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "UPDATE_SCHOOL_SUBSCRIPTION",
                "School",
                String.valueOf(schoolId),
                oldValue,
                "plan=" + updated.getPlan() + ",active=" + updated.isActive(),
                request.getRemoteAddr()
        );

        return SchoolSettingsResponse.from(updated);
    }

    /**
     * SUPER_ADMIN: list all schools, enriched with each school's admin userId.
     */
    public List<SchoolSettingsResponse> listAllSchools() {
        return schoolRepository.findAll().stream()
                .map(school -> {
                    SchoolSettingsResponse r = SchoolSettingsResponse.from(school);
                    userRepository.findFirstBySchoolIdAndRole(school.getId(), Role.ADMIN)
                            .ifPresent(admin -> r.setAdminUserId(admin.getUserId()));
                    return r;
                })
                .toList();
    }

    /**
     * SUPER_ADMIN: update all editable fields of a school (info + subscription).
     */
    @Transactional
    public SchoolSettingsResponse updateSchoolDetails(Long schoolId, SuperAdminSchoolUpdateRequest req,
                                                      HttpServletRequest request) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        String oldValue = "name=" + school.getName() + ",plan=" + school.getPlan() + ",active=" + school.isActive();

        if (req.getName() != null && !req.getName().isBlank()) school.setName(req.getName());
        if (req.getBoardType() != null) school.setBoardType(req.getBoardType());
        if (req.getAddress() != null) school.setAddress(req.getAddress());
        if (req.getCity() != null) school.setCity(req.getCity());
        if (req.getState() != null) school.setState(req.getState());
        if (req.getPincode() != null) school.setPincode(req.getPincode());
        if (req.getEmail() != null) school.setEmail(req.getEmail());
        if (req.getPhone() != null) school.setPhone(req.getPhone());
        if (req.getWebsite() != null) school.setWebsite(req.getWebsite());
        if (req.getContactPersonName() != null) school.setContactPersonName(req.getContactPersonName());
        if (req.getPlan() != null) school.setPlan(req.getPlan());
        if (req.getMaxStudents() != null) school.setMaxStudents(req.getMaxStudents());
        if (req.getExpiryDate() != null) school.setExpiryDate(req.getExpiryDate());
        if (req.getActive() != null) school.setActive(req.getActive());

        School updated = schoolRepository.save(school);
        log.info("Super admin updated school schoolId={}", schoolId);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "SUPER_ADMIN_UPDATE_SCHOOL",
                "School",
                String.valueOf(schoolId),
                oldValue,
                "name=" + updated.getName() + ",plan=" + updated.getPlan() + ",active=" + updated.isActive(),
                request.getRemoteAddr()
        );

        SchoolSettingsResponse r = SchoolSettingsResponse.from(updated);
        userRepository.findFirstBySchoolIdAndRole(schoolId, Role.ADMIN)
                .ifPresent(admin -> r.setAdminUserId(admin.getUserId()));
        return r;
    }

    /**
     * SUPER_ADMIN: reset the ADMIN user's password for a given school.
     */
    @Transactional
    public void resetAdminPassword(Long schoolId, String newPassword, HttpServletRequest request) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required.");
        }
        if (newPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters.");
        }
        User admin = userRepository.findFirstBySchoolIdAndRole(schoolId, Role.ADMIN)
                .orElseThrow(() -> new NoSuchElementException("No admin user found for school: " + schoolId));

        admin.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(admin);
        log.info("Super admin reset password for admin userId={} schoolId={}", admin.getUserId(), schoolId);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "RESET_ADMIN_PASSWORD",
                "User",
                admin.getUserId(),
                null,
                "Password reset by super admin for school " + schoolId,
                request.getRemoteAddr()
        );
    }

    /**
     * SUPER_ADMIN: toggle a school's active state (deactivate if active, activate if inactive).
     */
    @Transactional
    public void deleteSchool(Long schoolId, HttpServletRequest request) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        boolean wasActive = school.isActive();
        school.setActive(!wasActive);
        schoolRepository.save(school);
        log.info("Super admin {} schoolId={}", wasActive ? "deactivated" : "activated", schoolId);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                wasActive ? "DEACTIVATE_SCHOOL" : "ACTIVATE_SCHOOL",
                "School",
                String.valueOf(schoolId),
                "active=" + wasActive,
                "active=" + !wasActive,
                request.getRemoteAddr()
        );
    }

    /**
     * SUPER_ADMIN: platform-wide stats across all schools.
     */
    public SuperAdminDashboardDto getSuperAdminDashboard() {
        LocalDate today = LocalDate.now();
        long totalSchools   = schoolRepository.count();
        long activeSchools  = schoolRepository.countByActiveTrue();
        long totalStudents  = studentRepository.count();
        long totalTeachers  = teacherRepository.count();
        long revenueThisMonth = paymentRepository
                .sumAmountCollectedByMonthAndYear(today.getMonthValue(), today.getYear());
        return new SuperAdminDashboardDto(totalSchools, activeSchools, totalStudents, totalTeachers, revenueThisMonth);
    }

    /**
     * Returns the ordered active class names for the current school (used by frontend dropdowns).
     * Source: school_class table, ordered by display_order.
     */
    public List<String> getClassNames() {
        Long schoolId = securityUtil.getSchoolId();
        return schoolClassRepository.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)
                .stream()
                .map(SchoolClass::getName)
                .toList();
    }

    /**
     * Returns the full SchoolClass records for the current school (for the management UI).
     */
    public List<SchoolClass> getManagedClasses() {
        return schoolClassRepository.findBySchoolIdAndActiveOrderByDisplayOrderAsc(securityUtil.getSchoolId(), true);
    }

    /**
     * Adds a new class at the end of the current school's class list.
     * If a soft-deleted class with the same name exists, it is re-activated instead of
     * creating a new row (which would violate the unique constraint on school_id + name).
     */
    @Transactional
    public SchoolClass addClass(String name) {
        Long schoolId = securityUtil.getSchoolId();
        List<SchoolClass> all = schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(schoolId);

        // If a soft-deleted class with the same name exists, reactivate it
        String trimmed = name.trim();
        for (SchoolClass existing : all) {
            if (existing.getName().equalsIgnoreCase(trimmed) && !existing.isActive()) {
                int nextOrder = all.stream()
                        .filter(SchoolClass::isActive)
                        .mapToInt(c -> c.getDisplayOrder() == null ? 0 : c.getDisplayOrder())
                        .max().orElse(0) + 1;
                existing.setActive(true);
                existing.setDisplayOrder(nextOrder);
                return schoolClassRepository.save(existing);
            }
            if (existing.getName().equalsIgnoreCase(trimmed) && existing.isActive()) {
                throw new IllegalArgumentException("Class '" + trimmed + "' already exists.");
            }
        }

        int nextOrder = all.stream()
                .filter(SchoolClass::isActive)
                .mapToInt(c -> c.getDisplayOrder() == null ? 0 : c.getDisplayOrder())
                .max().orElse(0) + 1;
        SchoolClass sc = new SchoolClass();
        sc.setSchoolId(schoolId);
        sc.setName(trimmed);
        sc.setDisplayOrder(nextOrder);
        sc.setActive(true);
        return schoolClassRepository.save(sc);
    }

    /**
     * Soft-deletes a class. Validates it belongs to the current school.
     * Refuses if any students are enrolled in this class.
     */
    @Transactional
    public void deleteClass(Long classId) {
        Long schoolId = securityUtil.getSchoolId();
        SchoolClass sc = schoolClassRepository.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("Class not found: " + classId));
        if (!schoolId.equals(sc.getSchoolId())) {
            throw new SecurityException("Class does not belong to your school.");
        }
        long studentCount = studentRepository.countByClassNameAndSchoolId(sc.getName(), schoolId);
        if (studentCount > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete \"" + sc.getName() + "\" — " + studentCount +
                    " student" + (studentCount == 1 ? " is" : "s are") +
                    " currently enrolled in this class. Please move or remove them first."
            );
        }
        sc.setActive(false);
        schoolClassRepository.save(sc);
    }

    /**
     * Reorders classes by accepting an ordered list of class IDs.
     * Validates all IDs belong to the current school.
     */
    @Transactional
    public void reorderClasses(List<Long> orderedIds) {
        Long schoolId = securityUtil.getSchoolId();
        for (int i = 0; i < orderedIds.size(); i++) {
            SchoolClass sc = schoolClassRepository.findById(orderedIds.get(i))
                    .orElseThrow(() -> new NoSuchElementException("Class not found"));
            if (!schoolId.equals(sc.getSchoolId())) {
                throw new SecurityException("Class does not belong to your school.");
            }
            sc.setDisplayOrder(i + 1);
            schoolClassRepository.save(sc);
        }
    }

    /**
     * Toggles the stream-eligible flag for a class.
     * Validates the class belongs to the current school.
     */
    @Transactional
    public SchoolClass toggleStreamEligible(Long classId, boolean eligible) {
        Long schoolId = securityUtil.getSchoolId();
        SchoolClass sc = schoolClassRepository.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("Class not found: " + classId));
        if (!schoolId.equals(sc.getSchoolId())) {
            throw new SecurityException("Class does not belong to your school.");
        }
        sc.setStreamEligible(eligible);
        return schoolClassRepository.save(sc);
    }

    /**
     * Returns the names of all stream-eligible active classes for the current school.
     */
    public List<String> getStreamEligibleClassNames() {
        Long schoolId = securityUtil.getSchoolId();
        return schoolClassRepository.findBySchoolIdAndStreamEligibleAndActiveOrderByDisplayOrderAsc(schoolId, true, true)
                .stream()
                .map(SchoolClass::getName)
                .toList();
    }

    /**
     * Looks up a school name by its ID, returning a safe fallback if not found.
     * Used by email/PDF templates to replace hardcoded school name.
     */
    public String resolveSchoolName(Long schoolId) {
        if (schoolId == null) return "School";
        return schoolRepository.findById(schoolId)
                .map(School::getName)
                .orElse("School");
    }
}
