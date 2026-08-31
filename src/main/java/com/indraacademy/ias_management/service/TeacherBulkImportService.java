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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles CSV bulk import of teachers.
 *
 * Expected CSV header row and field mapping (columns are matched by name, not position —
 * see {@link #buildColumnIndex}, so old/new templates both work regardless of column order):
 *
 *  Column name     | Teacher field  | Required | Notes
 *  ----------------|----------------|----------|------------------------------
 *  Teacher Name    | name           | yes      |
 *  Email           | email          | yes      |
 *  Phone Number    | phoneNumber    | no       |
 *  Date of Birth   | dob            | yes      | yyyy-MM-dd — used as the initial login password (yyyyMMdd)
 *  Gender          | gender         | no       |
 *  Class Teacher   | classTeacher   | no       | class this teacher is class teacher of (e.g. "5", "Play group")
 *  Joining Date    | joiningDate    | yes      | yyyy-MM-dd
 *
 * Edunexify always generates the Employee ID for every newly imported teacher — a
 * "Teacher ID" column is no longer part of the downloadable template. An OLDER CSV that
 * still has one is still accepted without erroring — its column is simply never read into
 * the entity, and the result's {@code notice} field says so plainly.
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

    /** Column headers written to the downloadable template CSV — no ID column; Edunexify
     *  generates it. */
    public static final String[] TEMPLATE_HEADERS = {
            "Teacher Name", "Email", "Phone Number",
            "Date of Birth", "Gender", "Class Teacher", "Joining Date"
    };

    private static final String LEGACY_ID_COLUMN = "teacher id";

    private static final String LEGACY_ID_NOTICE =
            "This file included a 'Teacher ID' column. Edunexify now generates the account ID "
                    + "automatically for every new teacher, so the values in that column were not "
                    + "used. See the generated ID for each row below.";

    private static final DateTimeFormatter DOB_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired private TeacherService teacherService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WelcomeEmailService welcomeEmailService;

    /**
     * Parses the uploaded CSV, attempts to save each data row, and returns
     * an aggregated result containing success/failure counts and per-row errors.
     */
    public BulkImportResultDTO bulkImport(MultipartFile file, HttpServletRequest request) {
        List<BulkImportResultDTO.RowError> errors = new ArrayList<>();
        List<BulkImportResultDTO.RowSuccess> created = new ArrayList<>();
        int successful = 0;
        int totalRows  = 0;

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();
            if (header == null) {
                throw new IllegalArgumentException("CSV file is empty.");
            }
            Map<String, Integer> columnIndex = buildColumnIndex(header);
            boolean hasLegacyIdColumn = columnIndex.containsKey(LEGACY_ID_COLUMN);

            String[] row;
            int rowNum = 1; // header is row 1; data starts at row 2
            while ((row = reader.readNext()) != null) {
                rowNum++;
                if (isBlankRow(row)) continue;
                totalRows++;

                String name = getCol(row, columnIndex, "teacher name");
                try {
                    Teacher teacher = parseRow(row, columnIndex, rowNum, errors);
                    if (teacher == null) continue; // validation error already recorded

                    // Each addTeacher call runs in its own @Transactional context —
                    // a failure here does not affect rows already committed. teacherId is
                    // intentionally left unset on `teacher` here; addTeacher() generates it.
                    Teacher saved = teacherService.addTeacher(teacher, request);
                    createUserAccount(saved.getTeacherId(), saved.getName(), saved.getEmail(),
                            saved.getDob(), Role.TEACHER);
                    successful++;
                    created.add(new BulkImportResultDTO.RowSuccess(rowNum, saved.getName(), saved.getTeacherId()));
                    log.info("Bulk import: row {} saved (teacherId={})", rowNum, saved.getTeacherId());

                } catch (IllegalArgumentException e) {
                    log.warn("Bulk import: row {} rejected (name={}): {}", rowNum, name, e.getMessage());
                    errors.add(new BulkImportResultDTO.RowError(rowNum, name, e.getMessage()));
                } catch (Exception e) {
                    log.error("Bulk import: unexpected error on row {} (name={})", rowNum, name, e);
                    errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                            "Unexpected error: " + e.getMessage()));
                }
            }

            BulkImportResultDTO result = new BulkImportResultDTO(totalRows, successful, errors.size(), errors,
                    created, hasLegacyIdColumn ? LEGACY_ID_NOTICE : null);
            auditBulkImport(file.getOriginalFilename(), result, request);
            return result;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read CSV file during bulk import", e);
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a User login account for the imported teacher.
     * Initial password: DOB formatted as yyyyMMdd (e.g. "19880715") — DOB is a
     * required field for this import, so it is always present here. The
     * account is flagged mustChangePassword so the teacher is forced to set
     * a real password on first login.
     *
     * Sends the welcome email only after save() succeeds — a per-row failure never reaches
     * this point (caught by the caller's try/catch), so a retried import of an already-created
     * row cannot trigger a second send for the same teacher.
     */
    private void createUserAccount(String teacherId, String name, String email, LocalDate dob, String role) {
        String rawPassword = dob.format(DOB_FORMATTER);
        User user = new User();
        user.setUserId(teacherId);
        user.setEmail(email);
        user.setRole(role);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setSchoolId(securityUtil.getSchoolId());
        user.setMustChangePassword(true);
        userRepository.save(user);
        log.info("Bulk import: created User account for teacherId={}", teacherId);
        welcomeEmailService.sendWelcomeEmail(teacherId, name, role, email, user.getSchoolId());
    }

    /**
     * Writes a single BULK_IMPORT_TEACHER audit entry summarising the entire import session.
     * Each successfully saved row also has its own CREATE_TEACHER entry written by addTeacher().
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
            List<BulkImportResultDTO.RowError> errors,
            List<BulkImportResultDTO.RowSuccess> created
    ) {
        BulkImportAuditPayload(String filename, BulkImportResultDTO result) {
            this(filename, result.getTotalRows(), result.getSuccessful(),
                    result.getFailed(), result.getErrors(), result.getCreated());
        }
    }

    /**
     * Parses a single data row into a Teacher object. teacherId is deliberately left unset —
     * TeacherService.addTeacher() generates it. Any legacy "Teacher ID" column in the CSV is
     * simply never consulted here.
     * Returns {@code null} and appends to {@code errors} if any validation fails.
     */
    private Teacher parseRow(String[] row, Map<String, Integer> columnIndex, int rowNum,
                             List<BulkImportResultDTO.RowError> errors) {
        String name         = getCol(row, columnIndex, "teacher name");
        String email        = getCol(row, columnIndex, "email");
        String phoneNumber  = getCol(row, columnIndex, "phone number");
        String dobStr       = getCol(row, columnIndex, "date of birth");
        String gender       = getCol(row, columnIndex, "gender");
        String classTeacher = getCol(row, columnIndex, "class teacher");
        String joiningStr   = getCol(row, columnIndex, "joining date");

        // Required field checks
        if (name.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, "", "Teacher Name is required"));
            return null;
        }
        if (email.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name, "Email is required"));
            return null;
        }
        if (joiningStr.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name, "Joining Date is required"));
            return null;
        }
        if (dobStr.isEmpty()) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                    "Date of birth is required because it is used as the initial password."));
            return null;
        }

        // Date parsing
        LocalDate dob;
        try {
            dob = LocalDate.parse(dobStr);
        } catch (DateTimeParseException e) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                    "Invalid date format for 'Date of Birth', expected yyyy-MM-dd"));
            return null;
        }

        LocalDate joiningDate;
        try {
            joiningDate = LocalDate.parse(joiningStr);
        } catch (DateTimeParseException e) {
            errors.add(new BulkImportResultDTO.RowError(rowNum, name,
                    "Invalid date format for 'Joining Date', expected yyyy-MM-dd"));
            return null;
        }

        Teacher teacher = new Teacher();
        teacher.setName(name);
        teacher.setEmail(email);
        teacher.setPhoneNumber(phoneNumber.isEmpty()  ? null : phoneNumber);
        teacher.setDob(dob);
        teacher.setGender(gender.isEmpty()            ? null : gender);
        teacher.setClassTeacher(classTeacher.isEmpty() ? null : classTeacher);
        teacher.setJoiningDate(joiningDate);
        return teacher;
    }

    /** Maps each header cell (trimmed, lower-cased) to its column index, so columns are
     *  matched by name rather than fixed position. */
    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null) {
                index.put(header[i].trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return index;
    }

    /** Returns the trimmed cell value for the named column, or an empty string if that
     *  column isn't present in this file's header at all, or the cell itself is blank. */
    private String getCol(String[] row, Map<String, Integer> columnIndex, String columnName) {
        Integer idx = columnIndex.get(columnName);
        if (idx == null || idx >= row.length || row[idx] == null) return "";
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
