package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.TimetableBulkImportDtos.Result;
import com.indraacademy.ias_management.dto.TimetableBulkImportDtos.RowError;
import com.indraacademy.ias_management.dto.TimetableBulkImportDtos.RowSuccess;
import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.opencsv.CSVReader;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Handles CSV bulk import of timetable entries — one row per period slot, so a whole class's
 * weekly grid (or the whole school's) can be uploaded in one file instead of built one period
 * at a time through the Add Period modal.
 *
 * Expected CSV header row and field mapping (columns matched by name, not position):
 *
 *  Column name  | Entry field  | Required | Notes
 *  -------------|--------------|----------|---------------------------------------------
 *  Class        | className    | yes      | Must match an existing class exactly
 *  Section      | sectionId    | no       | Must match an existing section for that class; blank = whole class
 *  Day          | day          | yes      | Monday..Saturday (case-insensitive)
 *  Period       | periodNumber | yes      | Positive integer
 *  Start Time   | startTime    | yes      | HH:mm, 24-hour
 *  End Time     | endTime      | yes      | HH:mm, 24-hour, must be after Start Time
 *  Subject      | subjectName  | yes      |
 *  Teacher ID   | teacherId    | yes      | Must match an existing teacher in this school
 *  Simultaneous Group | simultaneousGroup | no | Blank = normal entry. A shared, admin-defined
 *                 tag (e.g. "MATH_BIO") lets two or more rows occupy the exact same
 *                 class+section+day+period+time — see TimetableValidationService.
 *
 * Processing rules:
 * - Each valid row is saved immediately, so a slot/teacher-schedule state already saved earlier
 *   in the same file is visible to {@link TimetableValidationService} when validating later rows
 *   — two rows conflicting with each other are caught exactly like a conflict against a row that
 *   already existed before the import, matching TimetableService.create()'s own behavior. This
 *   is also how two Simultaneous-Group rows in the same file correctly link to each other.
 * - A row whose Class doesn't match an existing school class is rejected rather than silently
 *   stored as free text: an unmatched class name would create a timetable no admin's class
 *   dropdown could ever surface.
 * - Blank rows are silently skipped. Row numbers in error reports are 1-indexed; row 1 is the header.
 */
@Service
public class TimetableBulkImportService {

    private static final Logger log = LoggerFactory.getLogger(TimetableBulkImportService.class);

    public static final String[] TEMPLATE_HEADERS = {
            "Class", "Section", "Day", "Period", "Start Time", "End Time", "Subject", "Teacher ID", "Simultaneous Group"
    };

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private TimetableValidationService timetableValidationService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    public Result bulkImport(MultipartFile file, HttpServletRequest request) {
        List<RowError> errors = new ArrayList<>();
        List<RowSuccess> created = new ArrayList<>();
        int totalRows = 0;
        Long schoolId = securityUtil.getSchoolId();

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();
            if (header == null) {
                throw new IllegalArgumentException("CSV file is empty.");
            }
            Map<String, Integer> columnIndex = buildColumnIndex(header);

            String[] row;
            int rowNum = 1; // header is row 1; data starts at row 2
            while ((row = reader.readNext()) != null) {
                rowNum++;
                if (isBlankRow(row)) continue;
                totalRows++;

                String label = "row " + rowNum;
                try {
                    TimetableEntry entry = parseRow(row, columnIndex, rowNum, schoolId, errors);
                    if (entry == null) continue; // validation error already recorded

                    label = buildLabel(entry);
                    timetableValidationService.validate(entry, schoolId, null);

                    entry.setSchoolId(schoolId);
                    TimetableEntry saved = timetableRepository.save(entry);
                    created.add(new RowSuccess(rowNum, label, saved.getId()));
                    log.info("Bulk import: row {} saved (timetableEntryId={})", rowNum, saved.getId());

                } catch (DataIntegrityViolationException e) {
                    // Expected business-rule rejection (slot conflict, group mismatch, teacher
                    // double-booking, exact duplicate) — the message is already specific and
                    // user-facing, not a bug, so it's reported as-is without an "Unexpected" prefix.
                    errors.add(new RowError(rowNum, label, e.getMessage()));
                } catch (Exception e) {
                    log.error("Bulk import: unexpected error on row {}", rowNum, e);
                    errors.add(new RowError(rowNum, label, "Unexpected error: " + e.getMessage()));
                }
            }

            Result result = new Result(totalRows, created.size(), errors.size(), errors, created);
            auditBulkImport(file.getOriginalFilename(), result, request);
            return result;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read CSV file during timetable bulk import", e);
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage(), e);
        }
    }

    private TimetableEntry parseRow(String[] row, Map<String, Integer> columnIndex, int rowNum,
                                     Long schoolId, List<RowError> errors) {
        String className   = getCol(row, columnIndex, "class");
        String sectionName = getCol(row, columnIndex, "section");
        String dayStr       = getCol(row, columnIndex, "day");
        String periodStr    = getCol(row, columnIndex, "period");
        String startTime    = getCol(row, columnIndex, "start time");
        String endTime      = getCol(row, columnIndex, "end time");
        String subjectName  = getCol(row, columnIndex, "subject");
        String teacherId    = getCol(row, columnIndex, "teacher id");
        String simultaneousGroup = getCol(row, columnIndex, "simultaneous group");

        if (className.isEmpty()) {
            errors.add(new RowError(rowNum, "row " + rowNum, "Class is required")); return null;
        }
        if (dayStr.isEmpty()) {
            errors.add(new RowError(rowNum, className, "Day is required")); return null;
        }
        if (periodStr.isEmpty()) {
            errors.add(new RowError(rowNum, className, "Period is required")); return null;
        }
        if (startTime.isEmpty() || endTime.isEmpty()) {
            errors.add(new RowError(rowNum, className, "Start Time and End Time are required")); return null;
        }
        if (subjectName.isEmpty()) {
            errors.add(new RowError(rowNum, className, "Subject is required")); return null;
        }
        if (teacherId.isEmpty()) {
            errors.add(new RowError(rowNum, className, "Teacher ID is required")); return null;
        }

        Optional<SchoolClass> schoolClass = schoolClassRepository.findBySchoolIdAndName(schoolId, className);
        if (schoolClass.isEmpty()) {
            errors.add(new RowError(rowNum, className, "Class '" + className + "' not found")); return null;
        }

        Long sectionId = null;
        if (!sectionName.isEmpty()) {
            Optional<Section> section = sectionRepository.findBySchoolIdAndClassIdAndName(
                    schoolId, schoolClass.get().getId(), sectionName);
            if (section.isEmpty()) {
                errors.add(new RowError(rowNum, className,
                        "Section '" + sectionName + "' not found for class '" + className + "'"));
                return null;
            }
            sectionId = section.get().getId();
        }

        Day day;
        try {
            day = Day.valueOf(dayStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new RowError(rowNum, className,
                    "Invalid Day '" + dayStr + "' — must be Monday, Tuesday, Wednesday, Thursday, Friday or Saturday"));
            return null;
        }

        int periodNumber;
        try {
            periodNumber = Integer.parseInt(periodStr);
            if (periodNumber < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            errors.add(new RowError(rowNum, className, "Period must be a positive whole number")); return null;
        }

        if (!TIME_PATTERN.matcher(startTime).matches() || !TIME_PATTERN.matcher(endTime).matches()) {
            errors.add(new RowError(rowNum, className, "Start Time and End Time must be in HH:mm 24-hour format")); return null;
        }
        if (startTime.compareTo(endTime) >= 0) {
            errors.add(new RowError(rowNum, className, "End Time must be after Start Time")); return null;
        }

        var teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId);
        if (teacher.isEmpty()) {
            errors.add(new RowError(rowNum, className, "Teacher ID '" + teacherId + "' not found")); return null;
        }

        TimetableEntry entry = new TimetableEntry();
        entry.setClassName(className);
        entry.setSectionId(sectionId);
        entry.setSectionName(sectionId != null ? sectionName : null);
        entry.setDay(day);
        entry.setPeriodNumber(periodNumber);
        entry.setStartTime(startTime);
        entry.setEndTime(endTime);
        entry.setSubjectName(subjectName);
        entry.setTeacherId(teacherId);
        entry.setTeacherName(teacher.get().getName());
        entry.setSimultaneousGroup(simultaneousGroup.isEmpty() ? null : simultaneousGroup);
        return entry;
    }

    private String buildLabel(TimetableEntry entry) {
        String cls = entry.getSectionName() != null ? entry.getClassName() + "-" + entry.getSectionName() : entry.getClassName();
        return cls + " · " + capitalize(entry.getDay().name()) + " · Period " + entry.getPeriodNumber();
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private void auditBulkImport(String filename, Result result, HttpServletRequest request) {
        try {
            String newValue = objectMapper.writeValueAsString(result);
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "BULK_IMPORT_TIMETABLE",
                    "TimetableEntry",
                    "BULK:" + (filename != null ? filename : "unknown"),
                    null,
                    newValue,
                    request.getRemoteAddr()
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize timetable bulk import audit payload", e);
        }
    }

    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null) {
                index.put(header[i].trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return index;
    }

    private String getCol(String[] row, Map<String, Integer> columnIndex, String columnName) {
        Integer idx = columnIndex.get(columnName);
        if (idx == null || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    private boolean isBlankRow(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }
}
