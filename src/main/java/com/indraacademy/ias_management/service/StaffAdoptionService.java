package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StaffAdoptionResponse;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.TeacherAttendanceRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.repository.UserSessionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StaffAdoptionService {
    static final String STARTED = "STARTED";
    static final String NOT_STARTED = "NOT_STARTED";
    static final String DISABLED = "DISABLED";
    static final String ACCOUNT_PENDING = "ACCOUNT_PENDING";

    private final TeacherRepository teachers;
    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final TeacherAttendanceRepository attendance;
    private final SecurityUtil security;

    public StaffAdoptionService(TeacherRepository teachers, UserRepository users,
                                UserSessionRepository sessions, TeacherAttendanceRepository attendance,
                                SecurityUtil security) {
        this.teachers = teachers;
        this.users = users;
        this.sessions = sessions;
        this.attendance = attendance;
        this.security = security;
    }

    /** Four bounded reads for a non-empty school; never one query per teacher. */
    @Transactional(readOnly = true)
    public StaffAdoptionResponse getStaffAdoption() {
        Long schoolId = security.getSchoolId();
        List<Teacher> schoolTeachers = teachers.findBySchoolId(schoolId);
        if (schoolTeachers.isEmpty()) return empty();

        List<String> teacherIds = schoolTeachers.stream().map(Teacher::getTeacherId).toList();
        Map<String, User> accounts = users.findBySchoolIdAndRoleAndUserIdIn(schoolId, Role.TEACHER, teacherIds)
                .stream().collect(Collectors.toMap(User::getUserId, Function.identity()));
        Map<String, Instant> lastActive = sessions.findLastActivityByUserIds(teacherIds).stream()
                .collect(Collectors.toMap(UserSessionRepository.LastActivity::getUserId,
                        UserSessionRepository.LastActivity::getLastActiveAt));
        Map<String, LocalDateTime> lastAttendance = attendance.findLastSelfCheckInBySchoolIdAndTeacherIds(schoolId, teacherIds)
                .stream().collect(Collectors.toMap(TeacherAttendanceRepository.LastTeacherCheckIn::getTeacherId,
                        TeacherAttendanceRepository.LastTeacherCheckIn::getLastAttendanceAt));

        List<StaffAdoptionResponse.TeacherRow> rows = schoolTeachers.stream()
                .map(teacher -> row(teacher, accounts.get(teacher.getTeacherId()),
                        lastActive.get(teacher.getTeacherId()), lastAttendance.get(teacher.getTeacherId())))
                .sorted(Comparator.comparing(StaffAdoptionResponse.TeacherRow::name,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        long disabled = rows.stream().filter(r -> DISABLED.equals(r.accountStatus())).count();
        long started = rows.stream().filter(r -> STARTED.equals(r.accountStatus())).count();
        long notStarted = rows.stream().filter(r -> NOT_STARTED.equals(r.accountStatus())
                || ACCOUNT_PENDING.equals(r.accountStatus())).count();
        long attendanceUsed = rows.stream().filter(StaffAdoptionResponse.TeacherRow::hasUsedAttendance).count();
        return new StaffAdoptionResponse(
                new StaffAdoptionResponse.Summary(rows.size(), started, notStarted, attendanceUsed, disabled), rows);
    }

    private StaffAdoptionResponse.TeacherRow row(Teacher teacher, User account, Instant lastActive,
                                                  LocalDateTime lastAttendance) {
        String status;
        if (teacher.getStatus() != TeacherStatus.ACTIVE || account != null && !account.isActive()) {
            status = DISABLED;
        } else if (account == null) {
            status = ACCOUNT_PENDING;
        } else {
            status = lastActive == null ? NOT_STARTED : STARTED;
        }
        return new StaffAdoptionResponse.TeacherRow(teacher.getTeacherId(), teacher.getName(), status,
                lastActive, lastAttendance != null, lastAttendance);
    }

    private StaffAdoptionResponse empty() {
        return new StaffAdoptionResponse(new StaffAdoptionResponse.Summary(0, 0, 0, 0, 0), List.of());
    }
}
