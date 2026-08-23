package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherAttendanceScheduleRequest;
import com.indraacademy.ias_management.dto.TeacherAttendanceScheduleResponse;
import com.indraacademy.ias_management.entity.TeacherAttendanceSchedule;
import com.indraacademy.ias_management.repository.TeacherAttendanceScheduleRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TeacherAttendanceScheduleService {
    public static final String SCHOOL = "SCHOOL";
    public static final String CUSTOM = "CUSTOM";
    private final TeacherAttendanceScheduleRepository repository;
    private final TeacherRepository teacherRepository;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public TeacherAttendanceScheduleService(TeacherAttendanceScheduleRepository repository,
            TeacherRepository teacherRepository, SecurityUtil securityUtil, AuditService auditService) {
        this.repository = repository;
        this.teacherRepository = teacherRepository;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<TeacherAttendanceScheduleResponse> history(String teacherId) {
        Long schoolId = securityUtil.getSchoolId();
        requireTeacher(teacherId, schoolId);
        return repository.findByTeacherIdAndSchoolIdOrderByEffectiveFromAsc(teacherId, schoolId)
                .stream().map(TeacherAttendanceScheduleResponse::from).toList();
    }

    @Transactional
    public TeacherAttendanceScheduleResponse change(String teacherId, TeacherAttendanceScheduleRequest request,
                                                      HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        requireTeacher(teacherId, schoolId);
        String type = request.getScheduleType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of(SCHOOL, CUSTOM).contains(type)) throw new IllegalArgumentException("Schedule type must be SCHOOL or CUSTOM.");
        String days = CUSTOM.equals(type) ? normalizeDays(request.getWorkingDays()) : null;

        List<TeacherAttendanceSchedule> history = repository
                .findByTeacherIdAndSchoolIdOrderByEffectiveFromAsc(teacherId, schoolId);
        LocalDate start = request.getEffectiveFrom();
        TeacherAttendanceSchedule target = history.stream()
                .filter(s -> start.equals(s.getEffectiveFrom())).findFirst().orElse(null);
        String oldValue = target == null ? null : describe(target);

        if (target == null) {
            target = new TeacherAttendanceSchedule();
            target.setSchoolId(schoolId);
            target.setTeacherId(teacherId);
            target.setEffectiveFrom(start);
            TeacherAttendanceSchedule previous = history.stream()
                    .filter(s -> s.getEffectiveFrom().isBefore(start))
                    .max(Comparator.comparing(TeacherAttendanceSchedule::getEffectiveFrom)).orElse(null);
            TeacherAttendanceSchedule next = history.stream()
                    .filter(s -> s.getEffectiveFrom().isAfter(start))
                    .min(Comparator.comparing(TeacherAttendanceSchedule::getEffectiveFrom)).orElse(null);
            if (previous != null) {
                previous.setEffectiveTo(start.minusDays(1));
                repository.save(previous);
            }
            target.setEffectiveTo(next == null ? null : next.getEffectiveFrom().minusDays(1));
        }
        target.setScheduleType(type);
        target.setWorkingDays(days);
        TeacherAttendanceSchedule saved = repository.save(target);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "CHANGE_TEACHER_ATTENDANCE_SCHEDULE",
                "TeacherAttendanceSchedule", String.valueOf(saved.getId()), oldValue, describe(saved), httpRequest.getRemoteAddr());
        return TeacherAttendanceScheduleResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Map<String, List<TeacherAttendanceSchedule>> schedulesByTeacher(Long schoolId, LocalDate start, LocalDate end) {
        return repository.findOverlapping(schoolId, start, end).stream()
                .collect(Collectors.groupingBy(TeacherAttendanceSchedule::getTeacherId));
    }

    public String workingDaysFor(String teacherId, LocalDate date, String schoolWorkingDays,
                                 Map<String, List<TeacherAttendanceSchedule>> schedules) {
        return schedules.getOrDefault(teacherId, List.of()).stream()
                .filter(s -> !date.isBefore(s.getEffectiveFrom()) && (s.getEffectiveTo() == null || !date.isAfter(s.getEffectiveTo())))
                .max(Comparator.comparing(TeacherAttendanceSchedule::getEffectiveFrom))
                .filter(s -> CUSTOM.equals(s.getScheduleType()))
                .map(TeacherAttendanceSchedule::getWorkingDays).orElse(schoolWorkingDays);
    }

    private void requireTeacher(String teacherId, Long schoolId) {
        teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
    }
    private String normalizeDays(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Select at least one custom working day.");
        LinkedHashSet<String> days = Arrays.stream(raw.split(",")).map(String::trim).map(String::toUpperCase)
                .filter(s -> !s.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String day : days) {
            try { DayOfWeek.valueOf(day); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid working day: " + day); }
        }
        if (days.isEmpty()) throw new IllegalArgumentException("Select at least one custom working day.");
        return Arrays.stream(DayOfWeek.values()).map(Enum::name).filter(days::contains).collect(Collectors.joining(","));
    }
    private String describe(TeacherAttendanceSchedule s) {
        return "type=" + s.getScheduleType() + ",days=" + s.getWorkingDays() + ",from=" + s.getEffectiveFrom() + ",to=" + s.getEffectiveTo();
    }
}
