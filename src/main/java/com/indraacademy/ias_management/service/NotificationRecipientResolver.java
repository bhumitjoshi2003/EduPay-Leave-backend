package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.notification.NotificationAudience;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves an audience once at publication time. It never runs during inbox reads. */
@Service
public class NotificationRecipientResolver {
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final ParentPortalService parentPortalService;

    public NotificationRecipientResolver(StudentRepository studentRepository,
                                         TeacherRepository teacherRepository,
                                         UserRepository userRepository,
                                         ParentPortalService parentPortalService) {
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.userRepository = userRepository;
        this.parentPortalService = parentPortalService;
    }

    public Set<String> resolve(Long schoolId, NotificationAudience audience) {
        if (schoolId == null || audience == null) throw new IllegalArgumentException("School and audience are required.");
        return switch (audience.type()) {
            case DIRECT_USER -> Set.of(requireEligibleDirectRecipient(audience.value(), schoolId));
            case STUDENT_WITH_LEAVE_PARENTS -> studentWithParents(schoolId, audience.value(), ParentPortalService.ChildPermission.MANAGE_LEAVE);
            case STUDENT_WITH_FEE_PARENTS -> studentWithParents(schoolId, audience.value(), ParentPortalService.ChildPermission.FEES);
            case STUDENT_WITH_ATTENDANCE_PARENTS -> studentWithParents(schoolId, audience.value(), ParentPortalService.ChildPermission.ATTENDANCE);
            case STUDENT_WITH_RESULT_PARENTS -> studentWithParents(schoolId, audience.value(), ParentPortalService.ChildPermission.RESULTS);
            case STUDENTS -> studentsAndParents(schoolId, null);
            case TEACHERS -> new LinkedHashSet<>(activeTeacherIds(schoolId));
            case PARENTS -> activeParentsFor(activeStudentIds(schoolId), schoolId);
            case CLASS -> studentsAndParents(schoolId, requireValue(audience));
            case CLASS_WITH_TEACHER -> classWithTeacher(schoolId, requireValue(audience));
            case ROLE -> byRole(schoolId, requireValue(audience));
            case WHOLE_SCHOOL -> wholeSchool(schoolId);
        };
    }

    private Set<String> studentWithParents(Long schoolId, String studentId,
                                           ParentPortalService.ChildPermission permission) {
        String eligibleStudent = requireEligibleDirectRecipient(studentId, schoolId);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(eligibleStudent);
        result.addAll(parentPortalService.findActiveParentIdsForStudents(
                schoolId, List.of(eligibleStudent), LocalDate.now(), permission));
        return result;
    }

    private Set<String> wholeSchool(Long schoolId) {
        LinkedHashSet<String> result = new LinkedHashSet<>(studentsAndParents(schoolId, null));
        result.addAll(activeTeacherIds(schoolId));
        return result;
    }

    private Set<String> byRole(Long schoolId, String role) {
        String normalized = role.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STUDENT" -> new LinkedHashSet<>(activeStudentIds(schoolId));
            case "TEACHER" -> new LinkedHashSet<>(activeTeacherIds(schoolId));
            case "PARENT" -> activeParentsFor(activeStudentIds(schoolId), schoolId);
            default -> userRepository.findBySchoolIdAndRoleAndActiveTrue(schoolId, normalized).stream()
                    .map(User::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        };
    }

    private Set<String> classWithTeacher(Long schoolId, String className) {
        LinkedHashSet<String> result = new LinkedHashSet<>(studentsAndParents(schoolId, className));
        teacherRepository.findByClassTeacherAndSchoolId(className, schoolId)
                .stream()
                .filter(t -> t.getStatus() == TeacherStatus.ACTIVE)
                .filter(t -> userRepository.findByUserIdAndSchoolIdAndActiveTrue(t.getTeacherId(), schoolId).isPresent())
                .forEach(t -> result.add(t.getTeacherId()));
        return result;
    }

    private Set<String> studentsAndParents(Long schoolId, String className) {
        List<String> students = className == null ? activeStudentIds(schoolId) : activeClassStudentIds(className, schoolId);
        LinkedHashSet<String> result = new LinkedHashSet<>(students);
        result.addAll(activeParentsFor(students, schoolId));
        return result;
    }

    private LinkedHashSet<String> activeParentsFor(List<String> studentIds, Long schoolId) {
        return new LinkedHashSet<>(parentPortalService.findActiveParentIdsForStudents(
                schoolId, studentIds, LocalDate.now()));
    }

    private List<String> activeStudentIds(Long schoolId) {
        return retainActiveUsers(studentRepository.findByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId)
                .stream().map(Student::getStudentId).toList(), schoolId);
    }

    private List<String> activeClassStudentIds(String className, Long schoolId) {
        return retainActiveUsers(studentRepository.findByClassNameAndStatusAndSchoolId(
                className, StudentStatus.ACTIVE, schoolId).stream().map(Student::getStudentId).toList(), schoolId);
    }

    private List<String> activeTeacherIds(Long schoolId) {
        return retainActiveUsers(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId)
                .stream().map(t -> t.getTeacherId()).toList(), schoolId);
    }

    private List<String> retainActiveUsers(Collection<String> ids, Long schoolId) {
        if (ids.isEmpty()) return List.of();
        Set<String> active = userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(schoolId, ids).stream()
                .map(User::getUserId).collect(Collectors.toSet());
        return ids.stream().filter(active::contains).toList();
    }

    private String requireEligibleDirectRecipient(String userId, Long schoolId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Direct recipient is required.");
        User user = userRepository.findByUserIdAndSchoolIdAndActiveTrue(userId.trim(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Recipient is not an active member of the target school."));
        if ("STUDENT".equalsIgnoreCase(user.getRole())) {
            Student student = studentRepository.findByStudentIdAndSchoolId(user.getUserId(), schoolId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Student recipient does not belong to the target school."));
            if (student.getStatus() != StudentStatus.ACTIVE || student.getStatus().isExitStatus()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Exited or inactive students cannot receive operational notifications.");
            }
        }
        return user.getUserId();
    }

    private String requireValue(NotificationAudience audience) {
        if (audience.value() == null || audience.value().isBlank()) {
            throw new IllegalArgumentException("Audience value is required for " + audience.type() + ".");
        }
        return audience.value().trim();
    }
}
