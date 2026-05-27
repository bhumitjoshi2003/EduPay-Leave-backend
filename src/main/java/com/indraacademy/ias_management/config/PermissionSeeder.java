package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.entity.Permission;
import com.indraacademy.ias_management.entity.RolePermission;
import com.indraacademy.ias_management.repository.PermissionRepository;
import com.indraacademy.ias_management.repository.RolePermissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class PermissionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionSeeder.class);

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedPermissions();
        seedDefaultRoleMappings();
    }

    private void seedPermissions() {
        List<Permission> definitions = List.of(
            // Student management
            new Permission("STUDENT_VIEW",       "View Students",          "STUDENT",      "View student list and details"),
            new Permission("STUDENT_CREATE",     "Register Students",      "STUDENT",      "Register new students"),
            new Permission("STUDENT_EDIT",       "Edit Students",          "STUDENT",      "Edit student details"),
            new Permission("STUDENT_DELETE",     "Delete Students",        "STUDENT",      "Remove students"),
            new Permission("STUDENT_BULK_IMPORT","Bulk Import Students",   "STUDENT",      "Import students via CSV"),
            new Permission("STUDENT_PROMOTE",    "Promote Students",       "STUDENT",      "Promote students to next class"),

            // Teacher management
            new Permission("TEACHER_VIEW",       "View Teachers",          "TEACHER",      "View teacher list and details"),
            new Permission("TEACHER_CREATE",     "Register Teachers",      "TEACHER",      "Register new teachers"),
            new Permission("TEACHER_EDIT",       "Edit Teachers",          "TEACHER",      "Edit teacher details"),
            new Permission("TEACHER_BULK_IMPORT","Bulk Import Teachers",   "TEACHER",      "Import teachers via CSV"),

            // Attendance
            new Permission("ATTENDANCE_MARK",    "Mark Attendance",        "ATTENDANCE",   "Mark daily attendance for a class"),
            new Permission("ATTENDANCE_VIEW",    "View Attendance",        "ATTENDANCE",   "View attendance summaries"),

            // Leave
            new Permission("LEAVE_APPLY",        "Apply for Leave",        "LEAVE",        "Submit leave applications"),
            new Permission("LEAVE_APPROVE",      "Approve/Reject Leaves",  "LEAVE",        "Approve or reject leave applications"),

            // Fee management
            new Permission("FEE_VIEW",           "View Fees",              "FEE",          "View fee structures and student fees"),
            new Permission("FEE_EDIT",           "Edit Fee Structure",     "FEE",          "Edit class-wise fee structures"),
            new Permission("FEE_COLLECT",        "Collect Payments",       "FEE",          "Record manual payments"),
            new Permission("FEE_REMIND",         "Send Fee Reminders",     "FEE",          "Send payment reminder notifications"),
            new Permission("PAYMENT_VIEW",       "View Payments",          "FEE",          "View payment history"),

            // Exams & results
            new Permission("EXAM_CONFIG",        "Configure Exams",        "EXAM",         "Configure exam types and schedules"),
            new Permission("MARK_ENTRY",         "Enter Marks",            "EXAM",         "Enter marks for students"),
            new Permission("RESULT_VIEW",        "View Results",           "EXAM",         "View exam results"),
            new Permission("REPORT_CARD",        "View Report Cards",      "EXAM",         "View and print report cards"),

            // School config
            new Permission("SCHOOL_SETTINGS",    "Manage School Settings", "SCHOOL",       "Edit school profile and settings"),
            new Permission("CLASS_MANAGE",       "Manage Classes",         "SCHOOL",       "Add/remove/reorder classes"),
            new Permission("SUBJECT_CONFIG",     "Configure Subjects",     "SCHOOL",       "Configure subjects per class"),
            new Permission("TIMETABLE_VIEW",     "View Timetable",        "SCHOOL",       "View class timetables"),
            new Permission("TIMETABLE_EDIT",     "Edit Timetable",        "SCHOOL",       "Edit class timetables"),
            new Permission("HOLIDAY_MANAGE",     "Manage Holidays",       "SCHOOL",       "Add/edit/remove school holidays"),

            // Communication
            new Permission("NOTICE_VIEW",        "View Notices",           "COMMUNICATION","View school notices"),
            new Permission("NOTICE_CREATE",      "Create Notices",         "COMMUNICATION","Create school-wide notices"),
            new Permission("EVENT_VIEW",         "View Events",            "COMMUNICATION","View school events"),
            new Permission("EVENT_CREATE",       "Create Events",          "COMMUNICATION","Create school events"),

            // Admin
            new Permission("AUDIT_VIEW",         "View Audit Logs",        "ADMIN",        "View audit log entries"),
            new Permission("ANALYTICS_VIEW",     "View Analytics",         "ADMIN",        "View analytics dashboard")
        );

        Set<String> existing = new HashSet<>();
        permissionRepository.findAll().forEach(p -> existing.add(p.getPermissionKey()));

        int created = 0;
        for (Permission def : definitions) {
            if (!existing.contains(def.getPermissionKey())) {
                permissionRepository.save(def);
                created++;
            }
        }
        if (created > 0) {
            log.info("Seeded {} new permissions ({} total defined)", created, definitions.size());
        }
    }

    private void seedDefaultRoleMappings() {
        // Only seed if no global (schoolId=null) mappings exist
        List<RolePermission> existingGlobal = rolePermissionRepository.findBySchoolIdIsNull();
        if (!existingGlobal.isEmpty()) {
            return;
        }

        log.info("Seeding default role-permission mappings...");

        Map<String, List<String>> roleMap = new LinkedHashMap<>();

        roleMap.put("ADMIN", List.of(
            "STUDENT_VIEW", "STUDENT_CREATE", "STUDENT_EDIT", "STUDENT_DELETE",
            "STUDENT_BULK_IMPORT", "STUDENT_PROMOTE",
            "TEACHER_VIEW", "TEACHER_CREATE", "TEACHER_EDIT", "TEACHER_BULK_IMPORT",
            "ATTENDANCE_MARK", "ATTENDANCE_VIEW",
            "LEAVE_APPROVE",
            "FEE_VIEW", "FEE_EDIT", "FEE_COLLECT", "FEE_REMIND", "PAYMENT_VIEW",
            "EXAM_CONFIG", "MARK_ENTRY", "RESULT_VIEW", "REPORT_CARD",
            "SCHOOL_SETTINGS", "CLASS_MANAGE", "SUBJECT_CONFIG",
            "TIMETABLE_VIEW", "TIMETABLE_EDIT", "HOLIDAY_MANAGE",
            "NOTICE_VIEW", "NOTICE_CREATE", "EVENT_VIEW", "EVENT_CREATE",
            "AUDIT_VIEW", "ANALYTICS_VIEW"
        ));

        roleMap.put("TEACHER", List.of(
            "STUDENT_VIEW",
            "ATTENDANCE_MARK", "ATTENDANCE_VIEW",
            "LEAVE_APPROVE",
            "MARK_ENTRY", "RESULT_VIEW", "REPORT_CARD",
            "TIMETABLE_VIEW",
            "NOTICE_VIEW", "EVENT_VIEW", "EVENT_CREATE"
        ));

        roleMap.put("STUDENT", List.of(
            "ATTENDANCE_VIEW",
            "LEAVE_APPLY",
            "FEE_VIEW", "PAYMENT_VIEW",
            "RESULT_VIEW", "REPORT_CARD",
            "TIMETABLE_VIEW",
            "NOTICE_VIEW", "EVENT_VIEW"
        ));

        roleMap.put("SUB_ADMIN", List.of(
            "STUDENT_VIEW",
            "ATTENDANCE_VIEW",
            "CLASS_MANAGE",
            "TIMETABLE_VIEW",
            "AUDIT_VIEW"
        ));

        Map<String, Permission> permCache = new HashMap<>();
        permissionRepository.findAll().forEach(p -> permCache.put(p.getPermissionKey(), p));

        int count = 0;
        for (Map.Entry<String, List<String>> entry : roleMap.entrySet()) {
            String role = entry.getKey();
            for (String key : entry.getValue()) {
                Permission perm = permCache.get(key);
                if (perm != null) {
                    rolePermissionRepository.save(new RolePermission(role, perm, null));
                    count++;
                }
            }
        }
        log.info("Seeded {} default role-permission mappings", count);
    }
}
