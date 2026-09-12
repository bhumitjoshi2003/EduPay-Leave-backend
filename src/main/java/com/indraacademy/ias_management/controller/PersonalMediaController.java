package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Authenticated delivery for personal profile photos; branding and event media stay public. */
@RestController
@RequestMapping("/api/uploads")
public class PersonalMediaController {
    private final StudentRepository students;
    private final TeacherRepository teachers;
    private final AdminRepository admins;
    private final SecurityUtil securityUtil;
    private final AuthService authService;
    private final ParentPortalService parentPortalService;

    @Value("${student.photo.directory:./uploads/student-photos}") private String studentDir;
    @Value("${teacher.photo.directory:./uploads/teacher-photos}") private String teacherDir;
    @Value("${admin.photo.directory:./uploads/admin-photos}") private String adminDir;

    public PersonalMediaController(StudentRepository students, TeacherRepository teachers,
                                   AdminRepository admins, SecurityUtil securityUtil,
                                   AuthService authService, ParentPortalService parentPortalService) {
        this.students = students; this.teachers = teachers; this.admins = admins;
        this.securityUtil = securityUtil; this.authService = authService;
        this.parentPortalService = parentPortalService;
    }

    @GetMapping("/student-photos/{filename:.+}")
    public ResponseEntity<Resource> studentPhoto(@PathVariable String filename) {
        String studentId = idFrom(filename);
        var student = students.findByStudentIdAndSchoolId(studentId, requiredSchoolId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String role = authService.getRole();
        if (Role.STUDENT.equals(role) && !studentId.equals(authService.getUserId())) deny();
        if (Role.PARENT.equals(role)) parentPortalService.assertChildAccess(studentId);
        requireStoredUrl(student.getPhotoUrl(), "student-photos", filename);
        return image(studentDir, filename);
    }

    @GetMapping("/teacher-photos/{filename:.+}")
    public ResponseEntity<Resource> teacherPhoto(@PathVariable String filename) {
        String teacherId = idFrom(filename);
        var teacher = teachers.findByTeacherIdAndSchoolId(teacherId, requiredSchoolId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String role = authService.getRole();
        if (!Role.ADMIN.equals(role) && !(Role.TEACHER.equals(role) && teacherId.equals(authService.getUserId()))) deny();
        requireStoredUrl(teacher.getPhotoUrl(), "teacher-photos", filename);
        return image(teacherDir, filename);
    }

    @GetMapping("/admin-photos/{filename:.+}")
    public ResponseEntity<Resource> adminPhoto(@PathVariable String filename) {
        String adminId = idFrom(filename);
        var admin = Role.SUPER_ADMIN.equals(authService.getRole())
                ? admins.findById(adminId)
                : admins.findByAdminIdAndSchoolId(adminId, requiredSchoolId());
        var found = admin.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!Role.SUPER_ADMIN.equals(authService.getRole()) && !Role.ADMIN.equals(authService.getRole())) deny();
        requireStoredUrl(found.getPhotoUrl(), "admin-photos", filename);
        return image(adminDir, filename);
    }

    private Long requiredSchoolId() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return schoolId;
    }

    private String idFrom(String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9_-]+\\.jpg")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return filename.substring(0, filename.length() - 4);
    }

    private void requireStoredUrl(String stored, String folder, String filename) {
        if (stored == null || !stored.equals("/uploads/" + folder + "/" + filename)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private ResponseEntity<Resource> image(String directory, String filename) {
        try {
            Path root = Paths.get(directory).toAbsolutePath().normalize();
            Path file = root.resolve(filename).normalize();
            if (!file.startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            Resource resource = new UrlResource(file.toUri());
            if (!resource.exists() || !resource.isReadable()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"profile.jpg\"")
                    .contentType(MediaType.IMAGE_JPEG).body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void deny() { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
}
