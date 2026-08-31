package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ParentBulkImportDtos.*;
import com.indraacademy.ias_management.entity.Parent;
import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.ParentRepository;
import com.indraacademy.ias_management.repository.ParentStudentRelationshipRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
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
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;

/**
 * Parent Bulk Import — one CSV row = one parent-student relationship. Matches your existing
 * Student/Teacher bulk import shape (header-name-based column parsing, one shared parse+
 * validate pass, per-row outcomes), but adds an extra Upload → Preview → Resolve → Confirm
 * step in front because — unlike Student/Teacher import — a Parent row can legitimately
 * refer to an ALREADY-EXISTING account, and getting that wrong risks one family seeing
 * another family's data. Nothing is written to the database until {@link #confirm} is
 * explicitly called; {@link #preview} only reads.
 *
 * <p>Deliberately stateless across the two calls — no persisted "batch" table. Both
 * {@code preview} and {@code confirm} independently re-parse and re-validate the same
 * uploaded file from scratch; {@code confirm} never trusts a client-cached preview as
 * authoritative, only the admin's explicit per-row resolutions for ambiguous rows.
 */
@Service
public class ParentBulkImportService {

    private static final Logger log = LoggerFactory.getLogger(ParentBulkImportService.class);

    public static final String[] TEMPLATE_HEADERS = {
            "Parent Name", "Phone", "Email", "Student ID", "Relationship"
    };

    @Autowired private ParentRepository parentRepository;
    @Autowired private ParentStudentRelationshipRepository relationshipRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AuditService auditService;
    @Autowired private IdGeneratorService idGeneratorService;
    @Autowired private PasswordResetService passwordResetService;
    @Autowired private SchoolRepository schoolRepository;

    // ─── Preview (read-only) ────────────────────────────────────────────────

    public PreviewResponse preview(MultipartFile file) {
        List<ParsedRow> parsed = parseFile(file);
        List<RowPreview> rows = analyze(parsed);
        return summarize(rows);
    }

    // ─── Confirm (creates accounts/relationships) ──────────────────────────

    @Transactional
    public ConfirmResponse confirm(MultipartFile file, ConfirmRequest confirmRequest, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        Map<Integer, RowResolution> resolutions = confirmRequest.resolutions() == null
                ? Map.of() : confirmRequest.resolutions();

        List<ParsedRow> parsed = parseFile(file);
        List<RowPreview> rows = analyze(parsed);

        // normalizedPhone|normalizedEmail -> Parent already created earlier in THIS confirm
        // call, so sibling rows never create a second Parent account. Keyed directly by
        // identity rather than by RowPreview.siblingGroup (which is only populated for
        // VALID_NEW_PARENT rows) so this also correctly groups two CONFLICT_* rows that
        // share the same phone+email and are BOTH resolved by the admin as "create new" —
        // without this, two rows for the same intended parent could otherwise each create
        // their own separate account.
        Map<String, Parent> createdByGroup = new HashMap<>();
        int parentsCreated = 0, relationshipsCreated = 0, skipped = 0;
        List<ConfirmedRow> created = new ArrayList<>();
        List<RowPreview> skippedRows = new ArrayList<>();

        for (RowPreview row : rows) {
            RowResolution resolution = resolutions.get(row.row());

            // Data-integrity problems are never resolvable by an identity decision —
            // always skipped regardless of what the admin sent for this row.
            if (row.status() == RowStatus.INVALID_STUDENT_ID || row.status() == RowStatus.STUDENT_EXITED
                    || row.status() == RowStatus.MISSING_REQUIRED_FIELD
                    || row.status() == RowStatus.DUPLICATE_ROW_IN_FILE
                    || row.status() == RowStatus.ALREADY_LINKED) {
                skipped++;
                skippedRows.add(row);
                continue;
            }

            RowAction action = resolveAction(row, resolution);
            if (action == RowAction.SKIP) {
                skipped++;
                skippedRows.add(row);
                continue;
            }

            try {
                Student student = studentRepository.findByStudentIdAndSchoolId(row.studentId(), schoolId)
                        .orElseThrow(() -> new IllegalStateException("Student no longer valid"));

                Parent parent;
                if (action == RowAction.LINK_EXISTING) {
                    String existingParentId = resolution != null && resolution.existingParentId() != null
                            ? resolution.existingParentId() : row.matchedParentId();
                    parent = parentRepository.findByParentIdAndSchoolId(existingParentId, schoolId)
                            .orElseThrow(() -> new IllegalStateException("Selected parent not found in this school"));
                } else {
                    // CREATE_NEW — reuse the Parent already created for another row in this
                    // same confirm call that shares this row's normalized phone+email, if one
                    // exists; otherwise create fresh.
                    String group = normalizePhone(row.phone()) + "|" + normalizeEmailOrEmpty(row.email());
                    parent = createdByGroup.get(group);
                    if (parent == null) {
                        if (isBlank(row.email())) {
                            skipped++;
                            skippedRows.add(withMessage(row, "Email is required to create a new parent account "
                                    + "(needed to send the account setup link)."));
                            continue;
                        }
                        parent = createParentAccount(row, schoolId);
                        parentsCreated++;
                        createdByGroup.put(group, parent);
                    }
                }

                String outcome = createRelationshipIfAbsent(parent, student, row.relationship(), schoolId, request);
                if ("created".equals(outcome)) relationshipsCreated++;
                created.add(new ConfirmedRow(row.row(), parent.getParentId(), student.getStudentId(), outcome));

            } catch (Exception e) {
                log.warn("Parent bulk import: row {} failed during confirm: {}", row.row(), e.getMessage());
                skipped++;
                skippedRows.add(withMessage(row, "Could not import: " + e.getMessage()));
            }
        }

        ConfirmResponse response = new ConfirmResponse(rows.size(), parentsCreated, relationshipsCreated,
                skipped, created, skippedRows);
        auditConfirm(file.getOriginalFilename(), response, request);
        return response;
    }

    private RowAction resolveAction(RowPreview row, RowResolution resolution) {
        if (resolution != null) return resolution.action();
        return switch (row.status()) {
            case VALID_NEW_PARENT -> RowAction.CREATE_NEW;
            case VALID_EXISTING_PARENT_MATCH -> RowAction.LINK_EXISTING;
            default -> RowAction.SKIP; // CONFLICT_* with no explicit admin decision
        };
    }

    private Parent createParentAccount(RowPreview row, Long schoolId) {
        String parentId = idGeneratorService.generateParentId();
        Parent parent = new Parent();
        parent.setParentId(parentId);
        parent.setSchoolId(schoolId);
        parent.setName(row.parentName());
        parent.setEmail(normalizeEmail(row.email()));
        parent.setPhoneNumber(row.phone());
        parent.setActive(true);
        parentRepository.save(parent);

        User user = new User();
        user.setUserId(parentId);
        user.setSchoolId(schoolId);
        user.setRole(Role.PARENT);
        user.setEmail(normalizeEmail(row.email()));
        user.setPassword(passwordEncoder.encode(randomPlaceholderPassword()));
        user.setMustChangePassword(true);
        user.setActive(true);
        userRepository.save(user);

        String schoolName = schoolRepository.findById(schoolId).map(School::getName).orElse("your school");
        passwordResetService.sendParentWelcomeLink(user, parent.getName(), schoolName);
        return parent;
    }

    /** Returns "created" or "already_linked" (skipped, no duplicate written). */
    private String createRelationshipIfAbsent(Parent parent, Student student, String relationshipType,
                                               Long schoolId, HttpServletRequest request) {
        if (relationshipRepository.findBySchoolIdAndParentIdAndStudentId(schoolId, parent.getParentId(), student.getStudentId()).isPresent()) {
            return "already_linked";
        }
        ParentStudentRelationship link = new ParentStudentRelationship();
        link.setSchoolId(schoolId);
        link.setParentId(parent.getParentId());
        link.setStudentId(student.getStudentId());
        link.setRelationshipType(relationshipType.trim().toUpperCase(Locale.ROOT));
        link.setPrimaryGuardian(false);
        // Standard Access — identical defaults to the single-link flow (ParentPortalService
        // .linkStudent's defaultTrue()), including the same Pay Fees requires View Fees rule.
        link.setCanViewAttendance(true);
        link.setCanViewFees(true);
        link.setCanPayFees(true);
        link.setCanViewResults(true);
        link.setCanViewTimetable(true);
        link.setCanManageLeave(true);
        link.setEffectiveFrom(LocalDate.now());
        link.setActive(true);
        relationshipRepository.save(link);

        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "LINK_PARENT_STUDENT",
                "ParentStudentRelationship", parent.getParentId() + ":" + student.getStudentId(),
                null, "Linked via Parent Bulk Import", request.getRemoteAddr());
        return "created";
    }

    private String randomPlaceholderPassword() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    // ─── Pre-fill helper ────────────────────────────────────────────────────

    /**
     * Builds a starter CSV from data the school already has on file, to reduce (not
     * eliminate) manual admin typing. Only fatherName/motherName are used — Student.email/
     * phoneNumber are the STUDENT's own contact fields, not established anywhere in the data
     * model as belonging to a parent, so they are deliberately never copied into Parent
     * Phone/Email. One row per known parent relationship (a student with both a father and
     * mother name on file gets two rows); Phone/Email are always left blank for the admin to
     * complete before uploading this same file as the real import. A student with neither
     * name on file produces no row at all — there's nothing genuinely known to pre-fill.
     */
    public byte[] buildPrefillCsv() {
        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = studentRepository.findByStatusAndSchoolId(
                com.indraacademy.ias_management.entity.StudentStatus.ACTIVE, schoolId);

        StringBuilder csv = new StringBuilder();
        csv.append("Student ID,Student Name,Class,Parent Name,Relationship,Phone,Email\r\n");
        for (Student s : students) {
            if (!isBlank(s.getFatherName())) {
                appendPrefillRow(csv, s, s.getFatherName(), "Father");
            }
            if (!isBlank(s.getMotherName())) {
                appendPrefillRow(csv, s, s.getMotherName(), "Mother");
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendPrefillRow(StringBuilder csv, Student s, String parentName, String relationship) {
        csv.append(csvEscape(s.getStudentId())).append(',')
           .append(csvEscape(s.getName())).append(',')
           .append(csvEscape(s.getClassName())).append(',')
           .append(csvEscape(parentName)).append(',')
           .append(relationship).append(",,\r\n");
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ─── Parsing + matching (shared by preview and confirm) ────────────────

    private record ParsedRow(int row, String parentName, String phone, String email,
                             String studentId, String relationship) {}

    private List<ParsedRow> parseFile(MultipartFile file) {
        List<ParsedRow> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null) throw new IllegalArgumentException("CSV file is empty.");
            Map<String, Integer> columnIndex = buildColumnIndex(header);

            String[] row;
            int rowNum = 1;
            while ((row = reader.readNext()) != null) {
                rowNum++;
                if (isBlankRow(row)) continue;
                rows.add(new ParsedRow(rowNum,
                        getCol(row, columnIndex, "parent name"),
                        getCol(row, columnIndex, "phone"),
                        getCol(row, columnIndex, "email"),
                        getCol(row, columnIndex, "student id"),
                        getCol(row, columnIndex, "relationship")));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage(), e);
        }
        return rows;
    }

    private List<RowPreview> analyze(List<ParsedRow> parsedRows) {
        Long schoolId = securityUtil.getSchoolId();
        List<Parent> existingParents = parentRepository.findBySchoolIdOrderByNameAsc(schoolId);

        // Rows already seen in THIS file, keyed by normalized (phone,email) — assigns a
        // stable sibling group id to every row sharing the same identity within the file.
        Map<String, Integer> groupByKey = new LinkedHashMap<>();
        Set<String> seenExactRows = new HashSet<>();
        List<RowPreview> results = new ArrayList<>();

        for (ParsedRow r : parsedRows) {
            String normPhone = normalizePhone(r.phone());
            // Coerced to "" rather than left null: every use below is a plain equality/
            // isEmpty() check, never a value written back to the DB (normalizeEmail(...) is
            // used again, separately, at actual Parent-creation time where null really does
            // mean "no email on file").
            String normEmailRaw = normalizeEmail(r.email());
            String normEmail = normEmailRaw == null ? "" : normEmailRaw;

            if (isBlank(r.parentName()) || isBlank(r.phone()) || isBlank(r.studentId()) || isBlank(r.relationship())) {
                results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                        RowStatus.MISSING_REQUIRED_FIELD, "Parent Name, Phone, Student ID and Relationship are all required.",
                        null, null, null, null));
                continue;
            }

            String exactKey = normPhone + "|" + normEmail + "|" + r.studentId().trim() + "|" + r.relationship().trim().toUpperCase(Locale.ROOT);
            if (!seenExactRows.add(exactKey)) {
                results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                        RowStatus.DUPLICATE_ROW_IN_FILE, "Identical row already appears earlier in this file.",
                        null, null, null, null));
                continue;
            }

            Student student = studentRepository.findByStudentIdAndSchoolId(r.studentId().trim(), schoolId).orElse(null);
            if (student == null) {
                results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                        RowStatus.INVALID_STUDENT_ID, "No student with this ID was found in your school.",
                        null, null, null, null));
                continue;
            }
            if (student.getStatus() != null && student.getStatus().isExitStatus()) {
                results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                        RowStatus.STUDENT_EXITED, "This student has left the school; a parent link cannot be created.",
                        student.getName(), student.getClassName(), null, null));
                continue;
            }

            // Matching against existing parents in this school.
            String phoneMatchId = null, emailMatchId = null, bothMatchId = null;
            for (Parent p : existingParents) {
                boolean phoneEq = normPhone.equals(normalizePhone(p.getPhoneNumber()));
                boolean emailEq = !normEmail.isEmpty() && normEmail.equals(normalizeEmail(p.getEmail()));
                if (phoneEq && emailEq) { bothMatchId = p.getParentId(); break; }
                if (phoneEq && phoneMatchId == null) phoneMatchId = p.getParentId();
                if (emailEq && emailMatchId == null) emailMatchId = p.getParentId();
            }

            if (bothMatchId != null) {
                if (relationshipRepository.findBySchoolIdAndParentIdAndStudentId(schoolId, bothMatchId, r.studentId().trim()).isPresent()) {
                    results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                            RowStatus.ALREADY_LINKED, "This parent is already linked to this student.",
                            student.getName(), student.getClassName(), bothMatchId, null));
                } else {
                    results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                            RowStatus.VALID_EXISTING_PARENT_MATCH, "Matches an existing parent account by phone and email.",
                            student.getName(), student.getClassName(), bothMatchId, null));
                }
                continue;
            }
            if (phoneMatchId != null) {
                results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                        RowStatus.CONFLICT_PHONE_MATCH_EMAIL_DIFFERS,
                        "Phone matches an existing parent, but the email is different or missing — needs review.",
                        student.getName(), student.getClassName(), phoneMatchId, null));
                continue;
            }
            if (emailMatchId != null) {
                results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                        RowStatus.CONFLICT_EMAIL_MATCH_PHONE_DIFFERS,
                        "Email matches an existing parent, but the phone is different or missing — needs review.",
                        student.getName(), student.getClassName(), emailMatchId, null));
                continue;
            }

            // No match anywhere — a new parent, grouped with any sibling rows in this file
            // that share the same normalized phone+email.
            int group = groupByKey.computeIfAbsent(normPhone + "|" + normEmail, k -> groupByKey.size());
            if (normEmail.isEmpty()) {
                results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                        RowStatus.MISSING_REQUIRED_FIELD,
                        "Email is required to create a new parent account (needed to send the account setup link).",
                        student.getName(), student.getClassName(), null, group));
                continue;
            }
            results.add(new RowPreview(r.row(), r.parentName(), r.phone(), r.email(), r.studentId(), r.relationship(),
                    RowStatus.VALID_NEW_PARENT, "New parent — will be created.",
                    student.getName(), student.getClassName(), null, group));
        }
        return results;
    }

    private RowPreview withMessage(RowPreview row, String message) {
        return new RowPreview(row.row(), row.parentName(), row.phone(), row.email(), row.studentId(), row.relationship(),
                row.status(), message, row.studentName(), row.className(), row.matchedParentId(), row.siblingGroup());
    }

    private PreviewResponse summarize(List<RowPreview> rows) {
        int newParent = 0, existingMatch = 0, conflict = 0, invalid = 0, duplicate = 0;
        for (RowPreview r : rows) {
            switch (r.status()) {
                case VALID_NEW_PARENT -> newParent++;
                case VALID_EXISTING_PARENT_MATCH -> existingMatch++;
                case CONFLICT_PHONE_MATCH_EMAIL_DIFFERS, CONFLICT_EMAIL_MATCH_PHONE_DIFFERS -> conflict++;
                case INVALID_STUDENT_ID, STUDENT_EXITED, MISSING_REQUIRED_FIELD -> invalid++;
                case DUPLICATE_ROW_IN_FILE, ALREADY_LINKED -> duplicate++;
            }
        }
        return new PreviewResponse(rows.size(), rows, newParent, existingMatch, conflict, invalid, duplicate);
    }

    private void auditConfirm(String filename, ConfirmResponse result, HttpServletRequest request) {
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "BULK_IMPORT_PARENT",
                "Parent", "BULK:" + (filename != null ? filename : "unknown"), null,
                "Parents created: " + result.parentsCreated() + ", relationships created: "
                        + result.relationshipsCreated() + ", skipped: " + result.skipped(),
                request.getRemoteAddr());
    }

    // ─── Small helpers ──────────────────────────────────────────────────────

    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null) index.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return index;
    }

    private String getCol(String[] row, Map<String, Integer> columnIndex, String columnName) {
        Integer idx = columnIndex.get(columnName);
        if (idx == null || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    private boolean isBlankRow(String[] row) {
        for (String cell : row) if (cell != null && !cell.trim().isEmpty()) return false;
        return true;
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private String normalizePhone(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** Same as normalizeEmail, but "" instead of null for a blank/missing email — safe to use
     *  directly as (part of) a Map key, unlike normalizeEmail's null-for-blank contract. */
    private String normalizeEmailOrEmpty(String email) {
        String normalized = normalizeEmail(email);
        return normalized == null ? "" : normalized;
    }
}
