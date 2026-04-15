package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.opencsv.CSVReader;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles CSV bulk import of teachers.
 *
 * Expected CSV header row and field mapping:
 *
 *  Column name     | Teacher field  | Required | Notes
 *  ----------------|----------------|----------|------------------------------
 *  Teacher ID      | teacherId      | yes      |
 *  Teacher Name    | name           | yes      |
 *  Email           | email          | yes      |
 *  Phone Number    | phoneNumber    | no       |
 *  Date of Birth   | dob            | no       | yyyy-MM-dd
 *  Gender          | gender         | no       |
 *  Class Teacher   | classTeacher   | no       | class this teacher is class teacher of (e.g. "5", "Play group")
 *  Joining Date    | joiningDate    | yes      | yyyy-MM-dd
 *
 * Processing rules:
 * - Each row is saved in its own transaction (via TeacherService.addTeacher).
 *   A failure on one row does not roll back previously saved rows.
 * - Blank rows are silently skipped.
 * - Row numbers in error reports are 1-indexed; row 1 is the header.
 */
@Service
public class TeacherBulkImportService {

    private static final Logger log = LoggerFactory.getLogger(TeacherBulkImportService.class);

    /** Column headers written to the downloadable template CSV. */
    public static final String[] TEMPLATE_HEADERS = {
            "Teacher ID", "Teacher Name", "Email", "Phone Number",
            "Date of Birth", "Gender", "Class Teacher", "Joining Date"
    };

    private static final DateTimeFormatter DOB_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired private TeacherService teacherService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /**
     * Parses the uploaded CSV, attempts to save each data row, and returns
     * an aggregated result containing success/failure counts and per-row errors.
     */
    public BulkImportResultDTO bulkImport(MultipartFile file, HttpServletRequest request) {
        List<BulkImportResultDTO.RowError> errors = new ArrayList<>();
        int successful = 0;
        int totalRows  = 0;

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();
            if (header == null) {
                throw new IllegalArgumentException("CSV file is empty.");
            }

            String[] row;
            int rowNum = 1; // header is row 1; data starts at row 2
            while ((row = reader.readNext()) != null) {
                rowNum++;
                if (isBlankRow(row)) continue;
                totalRows++;

                String teacherId = getCol(row, 0);
                try {
                    Teacher teacher = parseRow(row, rowNum, teacherId, errors);
                    if (teacher == null) continue; // validation error already recorded

                    // Each addTeacher call runs in its own @Transactional context —
                    // a failure here does not affect rows already committed.
                    teacherService.addTeacher(teacher, request);
                    createUserAccount(teacher.getTeacherId(), teacher.getEmail(),
                            teacher.getDob(), Role.TEACHER);
                    successful++;
                    log.info("Bulk import: row {} saved (teacherId={})", rowNum, teacherId);

                } catch (IllegalArgumentException e) {
                    // Covers duplicate ID and other business-rule rejections from addTeacher.
                    String reason = isDuplicateTeacherId(e.getMessage(), teacherId)
                            ? "Duplicate teacherId"
                            : e.getMessage();
                    log.warn("Bulk import: row {} rejected (teacherId={}): {}", rowNum, teacherId, reason);
                    errors.add(new BulkImportResultDTO.RowError(rowNum, teacherId, reason));
                } catch (Exception e) {
                    log.error("Bulk import: unexpected error on row {} (teacherId={})", rowNum, teacherId, e);
                    errors.add(new BulkImportResultDTO.RowError(rowNum, teacherId,
                            "Unexpected error: " + e.getMessage()));
                }
            }

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read CSV file during bulk import", e);
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage(), e);
        }

        BulkImportResultDTO result = new BulkImportResultDTO(totalRows, successful, errors.size(), errors);
        auditBulkImport(file.getOriginalFilename(), result, request);
        return result;
    }

    /**
     * Creates a User login account for the imported teacher.
     * Default password: DOB formatted as yyyyMMdd (e.g. "19880715").
     * Fallback (when DOB is absent): the teacherId itself.
     * The teacher should change this password on first login.
     */
    private void createUserAccount(String teacherId, String email, LocalDate dob, String role) {
        String rawPassword = (dob != null) ? dob.format(DOB_FORMATTER) : teacherId;
        User user = new User();
        user.setUserId(teacherId);
        user.setEmail(email);
        user.setRole(role);
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
        log.info("Bulk import: created User account for teacherId={} (password=DOB? {})",
                teacherId, dob != null);
    }

    /**
     * Writes a single BULK_IMPORT_TEACHER audit entry summarising the entire import session.
     * Each successfully saved row also has its own CREATE_TEACHER entry written by addTeacher().
     *
     * newValue JSON shape:
     * {
     *   "filename":   "teachers.csv",
     *   "totalRows":  50,
     *   "successful": 48,
     *   "failed":     2,
     *   "errors": [{ "row": 3, "studentId": "TCH_05", "reason": "Duplicate teacherId" }, ...]
     * }
     */
    private void auditBulkImport(String filename, BulkImportResultDTO result, HttpServletRequest request) {
        try {
            String newValue = objectMapper.writeValueAsString(new BulkImportAuditPayload(filename, result));
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "BULK_IMPORT_TEACHER",
                    "Teacher",
                    "BULK:" + (filename != null ? filename : "unknown"),
                    null,
                    newValue,
                    request.getRemoteAddr()
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize bulk import audit payload", e);
        }
    }

    /** Internal payload structure for the bulk import audit log entry. */
    private record BulkImportAuditPayload(
            String filename,
            int totalRows,
            int successful,
            int failed,
            List<BulkImportResultDTO.RowError> errors
    ) {
        BulkImportAuditPayload(String filename, BulkImportResultDTO result) {
            this(filename, result.getTotalRows(), result.getSuccessful(),
                    result.getFailed(), result.getErrors());
        }
    }

    /**
     * Parses a single data row into a Teacher object.
     * Returns {@code null} and appends to {@code errors} if any validation fails.
     */
    private Teacher parseRow(String[] row, int rowNum, String teacherId,
                             List<BulkImportResultDTO.RowError> errors) {
        // Column index mapping (matches TEMPLATE_HEADERS order)
        String name         = getCol(row, 1); // Teacher Name
        String email        = getCol(row, 2); // Email
        String phoneNumber  = getCol(row, 3); // Phone Number
        String dobStr       = getCol(row, 4); // Date of Birth
        String gender       = getCol(row, 5); // Gender
        String classTeacher = getCol(row, 6); // Class Teacher
        String joiningStr   = getCol(row, 7); // Joining Date

        // Required field checks
        if (teacherId.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, "", "Teacher ID is required"));
            return null;
        }
        if (name.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, teacherId, "Teacher Name is required"));
            return null;
        }
        if (email.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, teacherId, "Email is required"));
            return null;
        }
        if (joiningStr.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, teacherId, "Joining Date is required"));
            return null;
        }

        // Date parsing
        LocalDate dob = null;
        if (!dobStr.isEmpty()) {
            try {
                dob = LocalDate.parse(dobStr);
            } catch (DateTimeParseException e) {
                errors.add(new BulkImportResultDTO.RowError(rowNum, teacherId,
                        "Invalid date format for 'Date of Birth', expected yyyy-MM-dd"));
                return null;
            }
        }

        LocalDate joiningDate;
        try {
            joiningDate = LocalDate.parse(joiningStr);
        } catch (DateTimeParseException e) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, teacherId,
                    "Invalid date format for 'Joining Date', expected yyyy-MM-dd"));
            return null;
        }

        Teacher teacher = new Teacher();
        teacher.setTeacherId(teacherId);
        teacher.setName(name);
        teacher.setEmail(email);
        teacher.setPhoneNumber(phoneNumber.isEmpty()  ? null : phoneNumber);
        teacher.setDob(dob);
        teacher.setGender(gender.isEmpty()            ? null : gender);
        teacher.setClassTeacher(classTeacher.isEmpty() ? null : classTeacher);
        teacher.setJoiningDate(joiningDate);
        return teacher;
    }

    /**
     * Returns true when the exception message from addTeacher signals a duplicate ID.
     * addTeacher throws: "Teacher with ID <id> already exists."
     */
    private boolean isDuplicateTeacherId(String message, String teacherId) {
        return message != null && message.contains(teacherId) && message.contains("already exists");
    }

    /** Returns the trimmed cell value, or an empty string if the index is out of bounds. */
    private String getCol(String[] row, int idx) {
        if (idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    /** Returns true when every cell in the row is blank (skips fully empty lines). */
    private boolean isBlankRow(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }
}
