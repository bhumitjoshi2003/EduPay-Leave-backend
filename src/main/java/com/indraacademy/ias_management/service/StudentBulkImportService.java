package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.entity.Student;
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
 * Handles CSV bulk import of students.
 *
 * Expected CSV header row and field mapping:
 *
 *  Column name     | Student field  | Required | Notes
 *  ----------------|----------------|----------|------------------------------
 *  Student ID      | studentId      | yes      |
 *  Student Name    | name           | yes      |
 *  Email           | email          | yes      |
 *  Phone Number    | phoneNumber    | no       |
 *  Date of Birth   | dob            | no       | yyyy-MM-dd
 *  Class           | className      | yes      |
 *  Gender          | gender         | no       |
 *  Father Name     | fatherName     | no       |
 *  Mother Name     | motherName     | no       |
 *  Takes Bus       | takesBus       | no       | true / false (default: false)
 *  Distance (km)   | distance       | no       | numeric (default: 0.0)
 *  Joining Date    | joiningDate    | yes      | yyyy-MM-dd
 *  Leaving Date    | leavingDate    | no       | yyyy-MM-dd
 *
 * Processing rules:
 * - Each row is saved in its own transaction (via StudentService.addStudent).
 *   A failure on one row does not roll back previously saved rows.
 * - Blank rows are silently skipped.
 * - Row numbers in error reports are 1-indexed; row 1 is the header.
 */
@Service
public class StudentBulkImportService {

    private static final Logger log = LoggerFactory.getLogger(StudentBulkImportService.class);

    /** Column headers written to the downloadable template CSV. */
    public static final String[] TEMPLATE_HEADERS = {
            "Student ID", "Student Name", "Email", "Phone Number",
            "Date of Birth", "Class", "Gender", "Father Name", "Mother Name",
            "Takes Bus", "Distance (km)", "Joining Date", "Leaving Date"
    };

    private static final DateTimeFormatter DOB_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired private StudentService studentService;
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

                String studentId = getCol(row, 0);
                try {
                    Student student = parseRow(row, rowNum, studentId, errors);
                    if (student == null) continue; // validation error already recorded

                    // Each addStudent call runs in its own @Transactional context —
                    // a failure here does not affect rows already committed.
                    studentService.addStudent(student, request);
                    createUserAccount(student.getStudentId(), student.getEmail(),
                            student.getDob(), Role.STUDENT);
                    successful++;
                    log.info("Bulk import: row {} saved (studentId={})", rowNum, studentId);

                } catch (IllegalArgumentException e) {
                    // Covers duplicate ID and other business-rule rejections from addStudent.
                    log.warn("Bulk import: row {} rejected (studentId={}): {}", rowNum, studentId, e.getMessage());
                    errors.add(new BulkImportResultDTO.RowError(rowNum, studentId, e.getMessage()));
                } catch (Exception e) {
                    log.error("Bulk import: unexpected error on row {} (studentId={})", rowNum, studentId, e);
                    errors.add(new BulkImportResultDTO.RowError(rowNum, studentId,
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
     * Creates a User login account for the imported student.
     * Default password: DOB formatted as yyyyMMdd (e.g. "20050315").
     * Fallback (when DOB is absent): the studentId itself.
     * The student should change this password on first login.
     */
    private void createUserAccount(String studentId, String email, LocalDate dob, String role) {
        String rawPassword = (dob != null) ? dob.format(DOB_FORMATTER) : studentId;
        User user = new User();
        user.setUserId(studentId);
        user.setEmail(email);
        user.setRole(role);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setSchoolId(securityUtil.getSchoolId());
        userRepository.save(user);
        log.info("Bulk import: created User account for studentId={} (password=DOB? {})",
                studentId, dob != null);
    }

    /**
     * Writes a single BULK_IMPORT_STUDENT audit entry summarising the entire import session.
     * Each successfully saved row also has its own CREATE_STUDENT entry written by addStudent().
     *
     * newValue JSON shape:
     * {
     *   "filename":   "students.csv",
     *   "totalRows":  120,
     *   "successful": 115,
     *   "failed":     5,
     *   "errors": [{ "row": 3, "studentId": "Stu_12", "reason": "Duplicate studentId" }, ...]
     * }
     */
    private void auditBulkImport(String filename, BulkImportResultDTO result, HttpServletRequest request) {
        try {
            String newValue = objectMapper.writeValueAsString(new BulkImportAuditPayload(filename, result));
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "BULK_IMPORT_STUDENT",
                    "Student",
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
     * Parses a single data row into a Student object.
     * Returns {@code null} and appends to {@code errors} if any validation fails.
     */
    private Student parseRow(String[] row, int rowNum, String studentId,
                             List<BulkImportResultDTO.RowError> errors) {
        String name        = getCol(row, 1);
        String email       = getCol(row, 2);
        String phoneNumber = getCol(row, 3);
        String dobStr      = getCol(row, 4);
        String className   = getCol(row, 5);
        String gender      = getCol(row, 6);
        String fatherName  = getCol(row, 7);
        String motherName  = getCol(row, 8);
        String takesBusStr = getCol(row, 9);
        String distanceStr = getCol(row, 10);
        String joiningStr  = getCol(row, 11);
        String leavingStr  = getCol(row, 12);

        // Required field checks
        if (studentId.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, "", "Student ID is required"));
            return null;
        }
        if (name.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, studentId, "Student Name is required"));
            return null;
        }
        if (email.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, studentId, "Email is required"));
            return null;
        }
        if (className.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, studentId, "Class is required"));
            return null;
        }
        if (joiningStr.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, studentId, "Joining Date is required"));
            return null;
        }

        // Date parsing
        LocalDate dob = null;
        if (!dobStr.isEmpty()) {
            try {
                dob = LocalDate.parse(dobStr);
            } catch (DateTimeParseException e) {
                errors.add(new BulkImportResultDTO.RowError(rowNum, studentId,
                        "Invalid date format for 'Date of Birth', expected yyyy-MM-dd"));
                return null;
            }
        }

        LocalDate joiningDate;
        try {
            joiningDate = LocalDate.parse(joiningStr);
        } catch (DateTimeParseException e) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, studentId,
                    "Invalid date format for 'Joining Date', expected yyyy-MM-dd"));
            return null;
        }

        LocalDate leavingDate = null;
        if (!leavingStr.isEmpty()) {
            try {
                leavingDate = LocalDate.parse(leavingStr);
            } catch (DateTimeParseException e) {
                errors.add(new BulkImportResultDTO.RowError(rowNum, studentId,
                        "Invalid date format for 'Leaving Date', expected yyyy-MM-dd"));
                return null;
            }
        }

        // Boolean parsing
        Boolean takesBus = false;
        if (!takesBusStr.isEmpty()) {
            if ("true".equalsIgnoreCase(takesBusStr)) {
                takesBus = true;
            } else if ("false".equalsIgnoreCase(takesBusStr)) {
                takesBus = false;
            } else {
                errors.add(new BulkImportResultDTO.RowError(rowNum, studentId,
                        "Takes Bus must be 'true' or 'false'"));
                return null;
            }
        }

        // Numeric parsing
        Double distance = 0.0;
        if (!distanceStr.isEmpty()) {
            try {
                distance = Double.parseDouble(distanceStr);
            } catch (NumberFormatException e) {
                errors.add(new BulkImportResultDTO.RowError(rowNum, studentId,
                        "Distance must be a valid number"));
                return null;
            }
        }

        Student student = new Student();
        student.setStudentId(studentId);
        student.setName(name);
        student.setEmail(email);
        student.setPhoneNumber(phoneNumber.isEmpty()  ? null : phoneNumber);
        student.setDob(dob);
        student.setClassName(className);
        student.setGender(gender.isEmpty()             ? null : gender);
        student.setFatherName(fatherName.isEmpty()     ? null : fatherName);
        student.setMotherName(motherName.isEmpty()     ? null : motherName);
        student.setTakesBus(takesBus);
        student.setDistance(distance);
        student.setJoiningDate(joiningDate);
        student.setLeavingDate(leavingDate);
        return student;
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
