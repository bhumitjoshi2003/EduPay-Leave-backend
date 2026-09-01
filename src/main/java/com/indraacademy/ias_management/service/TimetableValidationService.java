package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.TimetableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Single, shared source of truth for timetable slot and teacher-conflict validation — used by
 * both {@link TimetableService} (manual create/update) and {@link TimetableBulkImportService}
 * (CSV import), so the two paths can never drift out of sync.
 *
 * <p>Slot rule: a candidate row may occupy a class+section+day+period slot that's already
 * occupied by other rows ONLY if all of them share the identical, non-blank
 * {@code simultaneousGroup} tag and the identical start/end time — this is the explicit signal
 * that distinguishes an intentional simultaneous/elective pairing (e.g. Mathematics/Biology,
 * both tagged "MATH_BIO") from an accidental duplicate. A blank/null group is always
 * strictly one-per-slot, exactly today's original behavior.
 *
 * <p>Teacher rule: independent of the slot rule — a teacher may never have two rows on the same
 * day whose start/end times overlap, regardless of class, section, period number, or
 * simultaneousGroup (a teacher literally cannot teach two places at once, even if the school
 * intentionally scheduled two subjects as "simultaneous" for the students).
 */
@Service
public class TimetableValidationService {

    @Autowired private TimetableRepository timetableRepository;

    /**
     * Validates a candidate entry against existing slot occupants and the teacher's schedule.
     * Throws {@link DataIntegrityViolationException} with a specific, human-readable message on
     * any violation. Call with {@code excludeId} set to the entry's own id when updating an
     * existing row (so it never conflicts with itself); {@code null} when creating.
     */
    public void validate(TimetableEntry candidate, Long schoolId, Long excludeId) {
        validateSlot(candidate, schoolId, excludeId);
        validateTeacherConflict(candidate, schoolId, excludeId);
    }

    private void validateSlot(TimetableEntry candidate, Long schoolId, Long excludeId) {
        List<TimetableEntry> existingInSlot = new java.util.ArrayList<>(fetchSlot(candidate, schoolId));
        existingInSlot.removeIf(e -> excludeId != null && excludeId.equals(e.getId()));
        if (existingInSlot.isEmpty()) return; // first entry in this slot — always fine

        String candidateGroup = normalizeGroup(candidate.getSimultaneousGroup());
        if (candidateGroup == null) {
            throw conflict("Period " + candidate.getPeriodNumber() + " on " + candidate.getDay()
                    + " is already assigned for " + slotLabel(candidate) + ".");
        }

        for (TimetableEntry existing : existingInSlot) {
            String existingGroup = normalizeGroup(existing.getSimultaneousGroup());
            if (existingGroup == null || !existingGroup.equals(candidateGroup)) {
                throw conflict("Period " + candidate.getPeriodNumber() + " on " + candidate.getDay()
                        + " is already assigned for " + slotLabel(candidate)
                        + " and is not part of the same simultaneous group.");
            }
            if (!Objects.equals(existing.getStartTime(), candidate.getStartTime())
                    || !Objects.equals(existing.getEndTime(), candidate.getEndTime())) {
                throw conflict("Simultaneous entries for the same period must share the same "
                        + "start and end time (existing: " + existing.getStartTime() + "-"
                        + existing.getEndTime() + ").");
            }
            if (existing.getSubjectName() != null
                    && existing.getSubjectName().equalsIgnoreCase(candidate.getSubjectName())
                    && Objects.equals(existing.getTeacherId(), candidate.getTeacherId())) {
                throw conflict("This subject/teacher assignment already exists for this period.");
            }
        }
    }

    private void validateTeacherConflict(TimetableEntry candidate, Long schoolId, Long excludeId) {
        if (candidate.getTeacherId() == null || candidate.getTeacherId().isBlank()) return;
        List<TimetableEntry> teacherDayEntries = timetableRepository.findByTeacherIdAndDayAndSchoolId(
                candidate.getTeacherId(), candidate.getDay(), schoolId);
        for (TimetableEntry other : teacherDayEntries) {
            if (excludeId != null && excludeId.equals(other.getId())) continue;
            if (overlaps(candidate.getStartTime(), candidate.getEndTime(), other.getStartTime(), other.getEndTime())) {
                throw conflict("Teacher " + candidate.getTeacherId() + " already has an overlapping period ("
                        + other.getStartTime() + "-" + other.getEndTime() + ", " + slotLabel(other)
                        + ") on " + candidate.getDay() + ".");
            }
        }
    }

    /** HH:mm strings compare correctly as plain strings (zero-padded, 24-hour) — same
     *  convention already used elsewhere in this codebase (frontend checkTimeConflict,
     *  TimetableBulkImportService's own start&lt;end check). */
    private boolean overlaps(String startA, String endA, String startB, String endB) {
        return startA.compareTo(endB) < 0 && startB.compareTo(endA) < 0;
    }

    private List<TimetableEntry> fetchSlot(TimetableEntry candidate, Long schoolId) {
        if (candidate.getSectionId() != null) {
            return timetableRepository.findByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolId(
                    candidate.getClassName(), candidate.getSectionId(), candidate.getDay(),
                    candidate.getPeriodNumber(), schoolId);
        }
        return timetableRepository.findByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                candidate.getClassName(), candidate.getDay(), candidate.getPeriodNumber(), schoolId);
    }

    private String slotLabel(TimetableEntry entry) {
        return entry.getSectionName() != null ? entry.getClassName() + "-" + entry.getSectionName() : entry.getClassName();
    }

    /** Blank/whitespace-only is treated identically to null — a CSV cell left empty must behave
     *  exactly like the column being absent. */
    private String normalizeGroup(String group) {
        if (group == null) return null;
        String trimmed = group.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private DataIntegrityViolationException conflict(String message) {
        return new DataIntegrityViolationException(message);
    }
}
