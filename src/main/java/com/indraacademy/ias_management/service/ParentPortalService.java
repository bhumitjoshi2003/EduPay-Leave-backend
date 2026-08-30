package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ParentDtos;
import com.indraacademy.ias_management.entity.Parent;
import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.ParentRepository;
import com.indraacademy.ias_management.repository.ParentStudentRelationshipRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class ParentPortalService {
    public enum ChildPermission { ATTENDANCE, FEES, PAY_FEES, RESULTS, TIMETABLE, MANAGE_LEAVE }
    private final ParentRepository parentRepository;
    private final ParentStudentRelationshipRepository relationshipRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityUtil securityUtil;
    private final EntitlementService entitlementService;

    public ParentPortalService(ParentRepository parentRepository,
                               ParentStudentRelationshipRepository relationshipRepository,
                               StudentRepository studentRepository,
                               UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               SecurityUtil securityUtil,
                               EntitlementService entitlementService) {
        this.parentRepository = parentRepository;
        this.relationshipRepository = relationshipRepository;
        this.studentRepository = studentRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityUtil = securityUtil;
        this.entitlementService = entitlementService;
    }

    @Transactional(readOnly = true)
    public List<ParentDtos.ParentSummary> listParents() {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        return parentRepository.findBySchoolIdOrderByNameAsc(schoolId).stream()
                .map(parent -> summary(parent, schoolId)).toList();
    }

    @Transactional
    public ParentDtos.ParentProfile createParent(ParentDtos.CreateParentRequest request) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        String parentId = request.parentId().trim();
        if (parentRepository.findByParentIdAndSchoolId(parentId, schoolId).isPresent()
                || userRepository.findByUserId(parentId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parent ID already exists");
        }
        String phoneNumber = request.phoneNumber().trim();
        String email = normalizeEmail(request.email());
        if (parentRepository.existsByPhoneNumberAndSchoolId(phoneNumber, schoolId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A parent account with this phone number already exists in this school");
        }
        if (email != null && parentRepository.existsByEmailIgnoreCaseAndSchoolId(email, schoolId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A parent account with this email already exists in this school");
        }

        Parent parent = new Parent();
        parent.setParentId(parentId);
        parent.setSchoolId(schoolId);
        parent.setName(request.name().trim());
        parent.setEmail(email);
        parent.setPhoneNumber(phoneNumber);
        parent.setActive(true);
        parentRepository.save(parent);

        User user = new User();
        user.setUserId(parentId);
        user.setSchoolId(schoolId);
        user.setRole(Role.PARENT);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.temporaryPassword()));
        user.setMustChangePassword(true);
        user.setActive(true);
        userRepository.save(user);
        return profile(parent, schoolId, false);
    }

    @Transactional
    public ParentDtos.ParentProfile linkStudent(String parentId, ParentDtos.LinkStudentRequest request) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        Parent parent = findParent(parentId, schoolId);
        Student student = studentRepository.findByStudentIdAndSchoolId(request.studentId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        if (student.getStatus() != null && student.getStatus().isExitStatus()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An exited student cannot be linked to an active parent account");
        }
        ParentStudentRelationship link = relationshipRepository
                .findBySchoolIdAndParentIdAndStudentId(schoolId, parentId, student.getStudentId())
                .orElseGet(ParentStudentRelationship::new);
        link.setSchoolId(schoolId);
        link.setParentId(parentId);
        link.setStudentId(student.getStudentId());
        link.setRelationshipType(request.relationshipType().trim().toUpperCase());
        if (request.primaryGuardian()) {
            relationshipRepository
                    .findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(schoolId, student.getStudentId())
                    .stream()
                    .filter(existing -> !parentId.equals(existing.getParentId()))
                    .filter(ParentStudentRelationship::isPrimaryGuardian)
                    .forEach(existing -> {
                        existing.setPrimaryGuardian(false);
                        relationshipRepository.save(existing);
                    });
        }
        link.setPrimaryGuardian(request.primaryGuardian());
        boolean canViewFees = defaultTrue(request.canViewFees());
        boolean canPayFees = defaultTrue(request.canPayFees());
        if (canPayFees && !canViewFees) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Fee payment permission requires fee viewing permission");
        }
        link.setCanViewAttendance(defaultTrue(request.canViewAttendance()));
        link.setCanViewFees(canViewFees);
        link.setCanPayFees(canPayFees);
        link.setCanViewResults(defaultTrue(request.canViewResults()));
        link.setCanViewTimetable(defaultTrue(request.canViewTimetable()));
        link.setCanManageLeave(defaultTrue(request.canManageLeave()));
        link.setPickupAuthorized(request.pickupAuthorized());
        link.setEffectiveFrom(request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom());
        link.setEffectiveUntil(request.effectiveUntil());
        if (link.getEffectiveUntil() != null && link.getEffectiveUntil().isBefore(link.getEffectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Effective-until date cannot precede effective-from date");
        }
        link.setActive(true);
        relationshipRepository.save(link);
        return profile(parent, schoolId, false);
    }

    /**
     * Ends all current guardian relationships when a student exits. This is an
     * internal lifecycle operation and deliberately does not depend on the
     * school's current Parent Portal entitlement.
     */
    @Transactional
    public void endRelationshipsForExitedStudent(Long schoolId, String studentId, LocalDate leavingDate) {
        LocalDate endDate = leavingDate == null ? LocalDate.now() : leavingDate;
        relationshipRepository.findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(schoolId, studentId)
                .stream()
                .filter(ParentStudentRelationship::isActive)
                .forEach(link -> {
                    link.setActive(false);
                    // Preserve the entity's date invariant for a future-dated link.
                    link.setEffectiveUntil(endDate.isBefore(link.getEffectiveFrom())
                            ? link.getEffectiveFrom() : endDate);
                    relationshipRepository.save(link);
                });
    }

    @Transactional
    public void unlinkStudent(String parentId, Long relationshipId) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        ParentStudentRelationship link = relationshipRepository.findByIdAndSchoolId(relationshipId, schoolId)
                .filter(item -> item.getParentId().equals(parentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent/student link not found"));
        link.setActive(false);
        link.setEffectiveUntil(LocalDate.now());
        relationshipRepository.save(link);
    }

    @Transactional
    public void setParentActive(String parentId, boolean active) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        Parent parent = findParent(parentId, schoolId);
        parent.setActive(active);
        parentRepository.save(parent);
        User user = userRepository.findByUserId(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent login not found"));
        if (!schoolId.equals(user.getSchoolId()) || !Role.PARENT.equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Parent login does not belong to this school");
        }
        user.setActive(active);
        if (!active) user.setRefreshTokenId(null);
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public ParentDtos.ParentProfile myProfile() {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        String parentId = securityUtil.getUsername();
        return profile(findParent(parentId, schoolId), schoolId, true);
    }

    @Transactional(readOnly = true)
    public ParentDtos.ParentProfile getParent(String parentId) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        return profile(findParent(parentId, schoolId), schoolId, false);
    }

    public void assertChildAccess(String studentId) {
        activeChildLink(studentId);
    }

    public void assertChildAccess(String studentId, ChildPermission permission) {
        ParentStudentRelationship link = activeChildLink(studentId);
        boolean allowed = switch (permission) {
            case ATTENDANCE -> link.isCanViewAttendance();
            case FEES -> link.isCanViewFees();
            case PAY_FEES -> link.isCanPayFees() && link.isCanViewFees();
            case RESULTS -> link.isCanViewResults();
            case TIMETABLE -> link.isCanViewTimetable();
            case MANAGE_LEAVE -> link.isCanManageLeave();
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This parent account does not have " + permission.name().toLowerCase().replace('_', ' ') + " access");
        }
    }

    private ParentStudentRelationship activeChildLink(String studentId) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        LocalDate today = LocalDate.now();
        ParentStudentRelationship link = relationshipRepository
                .findBySchoolIdAndParentIdAndStudentId(schoolId, securityUtil.getUsername(), studentId)
                .filter(ParentStudentRelationship::isActive)
                .filter(rel -> !rel.getEffectiveFrom().isAfter(today))
                .filter(rel -> rel.getEffectiveUntil() == null || !rel.getEffectiveUntil().isBefore(today))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You do not have access to this student"));
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You do not have access to this student"));
        if (student.getStatus() != null && student.getStatus().isExitStatus()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Parent access is unavailable for students who have left the school");
        }
        return link;
    }

    private ParentDtos.ParentProfile profile(Parent parent, Long schoolId, boolean activeOnly) {
        LocalDate today = LocalDate.now();
        List<ParentDtos.ChildAccess> children = relationshipRepository
                .findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(schoolId, parent.getParentId()).stream()
                // Inactive rows are retained for audit/history after unlinking, but they are not
                // current guardian links and must never be rendered as linked students.
                .filter(ParentStudentRelationship::isActive)
                .filter(link -> !activeOnly || (
                        !link.getEffectiveFrom().isAfter(today)
                        && (link.getEffectiveUntil() == null || !link.getEffectiveUntil().isBefore(today))))
                .filter(link -> !activeOnly || studentRepository
                        .findByStudentIdAndSchoolId(link.getStudentId(), schoolId)
                        .map(student -> student.getStatus() == null || !student.getStatus().isExitStatus())
                        .orElse(false))
                .map(link -> childAccess(link, schoolId)).toList();
        return new ParentDtos.ParentProfile(summary(parent, schoolId), children);
    }

    private ParentDtos.ChildAccess childAccess(ParentStudentRelationship link, Long schoolId) {
        Student student = studentRepository.findByStudentIdAndSchoolId(link.getStudentId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Linked student not found"));
        return new ParentDtos.ChildAccess(link.getId(), student.getStudentId(), student.getName(),
                student.getClassName(), student.getSectionName(), link.getRelationshipType(),
                link.isPrimaryGuardian(), link.isCanViewAttendance(), link.isCanViewFees(),
                link.isCanPayFees(), link.isCanViewResults(), link.isCanViewTimetable(), link.isCanManageLeave(),
                link.isPickupAuthorized(), link.getEffectiveFrom(), link.getEffectiveUntil());
    }

    private ParentDtos.ParentSummary summary(Parent parent, Long schoolId) {
        int count = (int) relationshipRepository
                .findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(schoolId, parent.getParentId()).stream()
                .filter(ParentStudentRelationship::isActive)
                .filter(link -> studentRepository.findByStudentIdAndSchoolId(link.getStudentId(), schoolId)
                        .map(student -> student.getStatus() == null || !student.getStatus().isExitStatus())
                        .orElse(false))
                .count();
        return new ParentDtos.ParentSummary(parent.getParentId(), parent.getName(), parent.getEmail(),
                parent.getPhoneNumber(), parent.isActive(), count);
    }

    private Parent findParent(String parentId, Long schoolId) {
        return parentRepository.findByParentIdAndSchoolId(parentId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent not found"));
    }

    private Long schoolId() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "School context is required");
        return schoolId;
    }

    private void requireFeature(Long schoolId) { entitlementService.requireFeature(schoolId, "PARENT_PORTAL"); }
    private boolean defaultTrue(Boolean value) { return value == null || value; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizeEmail(String value) {
        String email = blankToNull(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }
}
