package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.AdoptionOutcome;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.TimetableCandidate;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase F5A: classifies every {@code timetable_entry} row for one school against one explicit
 * target {@link AcademicSession}, and — only when invoked via {@link #classifyAndApply} — writes
 * the canonical {@code academicSessionId}/{@code classId}/{@code sectionId} for every row
 * classified {@link AdoptionOutcome#SAFE}. Never touches {@code day}, {@code periodNumber},
 * {@code startTime}, {@code endTime}, {@code subjectName}, {@code teacherId}, {@code teacherName}
 * or {@code simultaneousGroup} — those are the "actual timetable facts" F5A must preserve
 * verbatim.
 *
 * <p><b>Simultaneous-group handling</b>: a row's {@code simultaneousGroup} tag is never
 * normalized, renamed or repaired. A tag is flagged {@code simultaneousGroupConcern} (a semantic,
 * admin-facing observation) whenever this row would be the ONLY occupant of its class/section/day
 * /period slot carrying that tag — a genuine simultaneous pairing normally has 2+ rows sharing a
 * slot. This is deliberately independent of {@code outcome}: a lone tag does not itself violate
 * any rule, so it never by itself blocks adoption.
 *
 * <p><b>Note (post-timetable-rearchitecture)</b>: the live timetable write path
 * ({@link TimetableService}) no longer enforces any slot/teacher-overlap/subject-ownership
 * collision rule — any number of rows may occupy the same school/session/class/section/day/period.
 * This worker's own technical-conflict classification below (two candidates at the same slot with
 * different/missing tags, mismatched times, or a duplicate subject/teacher pair within the same
 * tag) is this tool's own, self-contained batch-classification logic for the separate,
 * one-time legacy-adoption use case — it was never delegated to the (now-removed)
 * {@code TimetableValidationService}, and is unaffected by its removal. It remains solely because
 * PROD currently has zero rows with {@code academic_session_id IS NULL} left to adopt (verified
 * read-only); revisit this tool's semantics if that ever changes.
 *
 * <p>Collision tracking (V55 slot uniqueness, teacher-overlap) is seeded from whatever already
 * legitimately occupies the target session (rows F3+ already wrote there, if any) and then grown
 * only by rows THIS pass classifies {@code SAFE} — a row that merely REQUIRES_ADMIN_CONFIRMATION
 * or is INVALID is never adopted, so it must never count as "occupying" a slot for the purposes of
 * classifying other candidates.
 */
@Service
public class LegacyTimetableAdoptionWorker {

    @Autowired private TimetableRepository timetableRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;

    @Transactional(readOnly = true)
    public ClassificationResult classify(Long schoolId, Long academicSessionId) {
        AcademicSession target = sessionAccess.requireOwnedSession(schoolId, academicSessionId);
        return classifyInternal(schoolId, target, false);
    }

    /** Locks the target session before re-classifying fresh, then writes every row this fresh
     *  pass finds SAFE — all inside this one transaction, so a failure partway through rolls back
     *  every write already made in this call (fail-closed, all-or-nothing for this dataset). The
     *  lock is acquired first (same ordering {@code ClassTeacherResponsibilityService}/
     *  {@code ClassTeacherActivationService} already use) so a concurrent responsibility write or
     *  activation apply() targeting the same session can never interleave with this adoption. */
    @Transactional
    public ClassificationResult classifyAndApply(Long schoolId, Long academicSessionId) {
        AcademicSession target = sessionAccess.lockWritableOwnedSession(schoolId, academicSessionId);
        ClassificationResult result = classifyInternal(schoolId, target, true);
        return result;
    }

    private ClassificationResult classifyInternal(Long schoolId, AcademicSession target, boolean write) {
        Map<String, SchoolClass> classesByName = new HashMap<>();
        for (SchoolClass c : schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(schoolId)) {
            classesByName.putIfAbsent(c.getName(), c);
        }
        Map<Long, List<Section>> sectionsByClassId = new HashMap<>();
        for (Section s : sectionRepository.findBySchoolIdOrderByClassIdAscDisplayOrderAsc(schoolId)) {
            if (s.isActive()) {
                sectionsByClassId.computeIfAbsent(s.getClassId(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<String, Teacher> teachersById = new HashMap<>();
        for (Teacher t : teacherRepository.findBySchoolId(schoolId)) {
            teachersById.put(t.getTeacherId(), t);
        }

        // Collision-tracking working sets, seeded from whatever is already genuinely in the
        // target session, then grown only by rows THIS pass accepts as SAFE.
        Set<String> occupiedUngroupedSlots = new HashSet<>();
        Map<String, List<TimetableEntry>> groupedSlotOccupants = new HashMap<>();
        Map<String, List<TimetableEntry>> teacherDaySchedule = new HashMap<>();
        for (TimetableEntry existing : timetableRepository.findByAcademicSessionIdAndSchoolId(target.getId(), schoolId)) {
            seed(existing, occupiedUngroupedSlots, groupedSlotOccupants, teacherDaySchedule);
        }

        List<TimetableEntry> allRows = new ArrayList<>(timetableRepository.findBySchoolId(schoolId));
        allRows.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        List<TimetableCandidate> details = new ArrayList<>(allRows.size());
        Counters counters = new Counters();
        // Deferred: a SAFE, grouped row's "lone tag" concern can only be judged once every row in
        // the batch has had a chance to join its slot — the FIRST row of a genuine matching pair
        // looks lone at the moment it's classified, purely because its partner hasn't been
        // registered into groupedSlotOccupants yet. See the post-pass below.
        List<Integer> pendingConcernIndexes = new ArrayList<>();
        List<String> pendingConcernSlotKeys = new ArrayList<>();

        for (TimetableEntry row : allRows) {
            counters.scanned++;

            if (row.getAcademicSessionId() != null) {
                if (row.getAcademicSessionId().equals(target.getId()) && row.getClassId() != null) {
                    counters.alreadyAdopted++;
                    details.add(candidate(row, AdoptionOutcome.ALREADY_ADOPTED,
                            "Already carries this exact target session and a resolved classId.",
                            row.getClassId(), row.getSectionId(), false, null));
                } else {
                    counters.skipped++;
                    details.add(candidate(row, AdoptionOutcome.SKIPPED,
                            "Already belongs to a different academic session (id=" + row.getAcademicSessionId()
                                    + ") than the requested target; out of scope for this adoption run.",
                            null, null, false, null));
                }
                continue;
            }

            Classification c = classifyLegacyRow(row, target, classesByName, sectionsByClassId, teachersById,
                    occupiedUngroupedSlots, groupedSlotOccupants, teacherDaySchedule, counters);
            details.add(candidate(row, c.outcome, c.reason, c.resolvedClassId, c.resolvedSectionId,
                    false, null));

            switch (c.outcome) {
                case SAFE -> {
                    counters.safe++;
                    register(row, c, occupiedUngroupedSlots, groupedSlotOccupants, teacherDaySchedule);
                    if (c.groupTag != null) {
                        pendingConcernIndexes.add(details.size() - 1);
                        pendingConcernSlotKeys.add(slotKey(c.resolvedClassId, c.resolvedSectionId, row.getDay(), row.getPeriodNumber()));
                    }
                    if (write) {
                        row.setAcademicSessionId(target.getId());
                        row.setClassId(c.resolvedClassId);
                        row.setSectionId(c.resolvedSectionId);
                        timetableRepository.save(row);
                        counters.adopted++;
                    }
                }
                case REQUIRES_ADMIN_CONFIRMATION -> counters.requiresAdminConfirmation++;
                case INVALID_OR_CONFLICTING -> counters.invalidOrConflicting++;
                default -> { /* ALREADY_ADOPTED / SKIPPED handled above */ }
            }
        }

        // Post-pass: now that every SAFE row has been registered, groupedSlotOccupants reflects
        // each slot's TRUE final membership (a conflicting different tag would already have
        // rejected any row that didn't match, so every entry sharing a key shares one tag).
        for (int i = 0; i < pendingConcernIndexes.size(); i++) {
            int idx = pendingConcernIndexes.get(i);
            String slotKey = pendingConcernSlotKeys.get(i);
            int finalMemberCount = groupedSlotOccupants.getOrDefault(slotKey, List.of()).size();
            if (finalMemberCount < 2) {
                TimetableCandidate old = details.get(idx);
                details.set(idx, new TimetableCandidate(
                        old.timetableEntryId(), old.legacyClassName(), old.legacySectionName(), old.day(),
                        old.periodNumber(), old.startTime(), old.endTime(), old.subjectName(), old.teacherId(),
                        old.simultaneousGroup(), old.outcome(), old.reason(), old.resolvedClassId(),
                        old.resolvedSectionId(), true,
                        "simultaneous_group '" + old.simultaneousGroup() + "' has no partner row sharing this "
                                + "exact class/section/day/period slot — a genuine simultaneous pairing normally "
                                + "has 2+ rows here. Technically safe to adopt as-is; the tag's intent should "
                                + "still be confirmed with an admin."));
                counters.simultaneousGroupConcerns++;
            }
        }

        return new ClassificationResult(counters, details);
    }

    private Classification classifyLegacyRow(
            TimetableEntry row,
            AcademicSession target,
            Map<String, SchoolClass> classesByName,
            Map<Long, List<Section>> sectionsByClassId,
            Map<String, Teacher> teachersById,
            Set<String> occupiedUngroupedSlots,
            Map<String, List<TimetableEntry>> groupedSlotOccupants,
            Map<String, List<TimetableEntry>> teacherDaySchedule,
            Counters counters) {

        SchoolClass schoolClass = classesByName.get(row.getClassName());
        if (schoolClass == null) {
            counters.unresolvedClassMappings++;
            return Classification.invalid(
                    "Unknown class: legacy class_name '" + row.getClassName()
                            + "' does not match any SchoolClass configured for this school.");
        }
        Long resolvedClassId = schoolClass.getId();

        List<Section> sectionsForClass = sectionsByClassId.getOrDefault(resolvedClassId, List.of());
        boolean hasSections = !sectionsForClass.isEmpty();
        Long resolvedSectionId = null;
        String legacySectionName = row.getSectionName();
        if (!hasSections) {
            if (legacySectionName != null && !legacySectionName.isBlank()) {
                counters.unresolvedSectionMappings++;
                return Classification.requiresConfirmation(
                        "Class '" + row.getClassName() + "' has no configured sections, but this legacy row "
                                + "carries section_name='" + legacySectionName + "' — verify before adopting.",
                        resolvedClassId, null);
            }
        } else {
            if (legacySectionName == null || legacySectionName.isBlank()) {
                counters.unresolvedSectionMappings++;
                return Classification.invalid(
                        "Class '" + row.getClassName() + "' has sections configured, but this legacy row has "
                                + "no section_name — a section is required and cannot be guessed.");
            }
            Section matched = sectionsForClass.stream()
                    .filter(s -> s.getName().equals(legacySectionName))
                    .findFirst().orElse(null);
            if (matched == null) {
                counters.unresolvedSectionMappings++;
                return Classification.invalid(
                        "Unknown section: '" + legacySectionName + "' does not exactly match any section of class '"
                                + row.getClassName() + "'.");
            }
            resolvedSectionId = matched.getId();
        }

        String teacherId = row.getTeacherId();
        Teacher teacher = null;
        if (teacherId != null && !teacherId.isBlank()) {
            teacher = teachersById.get(teacherId);
            if (teacher == null) {
                counters.invalidOrIneligibleTeachers++;
                return Classification.invalid(
                        "Unknown teacher: '" + teacherId + "' does not resolve to any teacher in this school.");
            }
            if (teacher.getStatus() != TeacherStatus.ACTIVE) {
                counters.invalidOrIneligibleTeachers++;
                return Classification.requiresConfirmation(
                        "Teacher '" + teacherId + "' is not ACTIVE (status=" + teacher.getStatus()
                                + ") — confirm whether this historical assignment should still be adopted.",
                        resolvedClassId, resolvedSectionId);
            }
        }

        if (row.getStartTime() == null || row.getEndTime() == null
                || row.getStartTime().compareTo(row.getEndTime()) >= 0) {
            return Classification.invalid(
                    "Malformed slot: start_time/end_time are missing or not chronologically ordered.");
        }

        String ungroupedKey = slotKey(resolvedClassId, resolvedSectionId, row.getDay(), row.getPeriodNumber());
        String groupTag = normalizeGroup(row.getSimultaneousGroup());

        if (groupTag == null) {
            if (occupiedUngroupedSlots.contains(ungroupedKey)) {
                counters.slotConflicts++;
                return Classification.invalid(
                        "Slot conflict: class/section/day/period is already claimed by another row adopted in "
                                + "this same run or already present in the target session (V55 uniqueness).");
            }
        } else {
            List<TimetableEntry> occupants = groupedSlotOccupants.getOrDefault(ungroupedKey, List.of());
            boolean differentTagPresent = occupants.stream()
                    .anyMatch(e -> !groupTag.equals(normalizeGroup(e.getSimultaneousGroup())));
            if (differentTagPresent) {
                counters.slotConflicts++;
                return Classification.invalid(
                        "Slot conflict: class/section/day/period is already occupied by a row with a different "
                                + "(or missing) simultaneous_group tag.");
            }
            List<TimetableEntry> sameTagOccupants = occupants.stream()
                    .filter(e -> groupTag.equals(normalizeGroup(e.getSimultaneousGroup())))
                    .toList();
            boolean timeMismatch = sameTagOccupants.stream()
                    .anyMatch(e -> !Objects.equals(e.getStartTime(), row.getStartTime())
                            || !Objects.equals(e.getEndTime(), row.getEndTime()));
            if (timeMismatch) {
                counters.slotConflicts++;
                return Classification.invalid(
                        "Simultaneous-group conflict: members of group '" + groupTag
                                + "' at this slot do not share the same start/end time.");
            }
            boolean duplicateSubjectTeacher = sameTagOccupants.stream()
                    .anyMatch(e -> e.getSubjectName() != null
                            && e.getSubjectName().equalsIgnoreCase(row.getSubjectName())
                            && Objects.equals(e.getTeacherId(), row.getTeacherId()));
            if (duplicateSubjectTeacher) {
                counters.slotConflicts++;
                return Classification.invalid(
                        "Simultaneous-group conflict: a row with the same subject/teacher pair already exists in "
                                + "group '" + groupTag + "' at this slot.");
            }
            // Whether this row ends up "lone" (no partner sharing its tag at this slot) can only
            // be judged once the WHOLE batch has been classified — see the post-pass in the
            // caller. This method only clears technical conflicts; it never decides the concern.
        }

        if (teacher != null) {
            String tdKey = teacher.getTeacherId() + "|" + row.getDay();
            List<TimetableEntry> sameDay = teacherDaySchedule.getOrDefault(tdKey, List.of());
            boolean overlap = sameDay.stream().anyMatch(e ->
                    overlaps(row.getStartTime(), row.getEndTime(), e.getStartTime(), e.getEndTime()));
            if (overlap) {
                counters.teacherOverlaps++;
                return Classification.invalid(
                        "Teacher overlap: '" + teacherId + "' would have two overlapping periods on "
                                + row.getDay() + " once this batch is adopted.");
            }
        }

        return Classification.safe(resolvedClassId, resolvedSectionId, groupTag);
    }

    private void seed(TimetableEntry existing, Set<String> occupiedUngroupedSlots,
            Map<String, List<TimetableEntry>> groupedSlotOccupants,
            Map<String, List<TimetableEntry>> teacherDaySchedule) {
        String key = slotKey(existing.getClassId(), existing.getSectionId(), existing.getDay(), existing.getPeriodNumber());
        String tag = normalizeGroup(existing.getSimultaneousGroup());
        if (tag == null) {
            occupiedUngroupedSlots.add(key);
        } else {
            groupedSlotOccupants.computeIfAbsent(key, k -> new ArrayList<>()).add(existing);
        }
        if (existing.getTeacherId() != null && !existing.getTeacherId().isBlank()) {
            teacherDaySchedule.computeIfAbsent(existing.getTeacherId() + "|" + existing.getDay(), k -> new ArrayList<>())
                    .add(existing);
        }
    }

    private void register(TimetableEntry row, Classification c, Set<String> occupiedUngroupedSlots,
            Map<String, List<TimetableEntry>> groupedSlotOccupants,
            Map<String, List<TimetableEntry>> teacherDaySchedule) {
        String key = slotKey(c.resolvedClassId, c.resolvedSectionId, row.getDay(), row.getPeriodNumber());
        if (c.groupTag == null) {
            occupiedUngroupedSlots.add(key);
        } else {
            groupedSlotOccupants.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        if (row.getTeacherId() != null && !row.getTeacherId().isBlank()) {
            teacherDaySchedule.computeIfAbsent(row.getTeacherId() + "|" + row.getDay(), k -> new ArrayList<>())
                    .add(row);
        }
    }

    private String slotKey(Long classId, Long sectionId, Object day, Integer period) {
        return classId + "|" + (sectionId == null ? "-" : sectionId) + "|" + day + "|" + period;
    }

    private String normalizeGroup(String group) {
        if (group == null) return null;
        String trimmed = group.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean overlaps(String startA, String endA, String startB, String endB) {
        return startA.compareTo(endB) < 0 && startB.compareTo(endA) < 0;
    }

    private TimetableCandidate candidate(TimetableEntry row, AdoptionOutcome outcome, String reason,
            Long resolvedClassId, Long resolvedSectionId, boolean concern, String concernReason) {
        return new TimetableCandidate(
                row.getId(), row.getClassName(), row.getSectionName(),
                row.getDay() != null ? row.getDay().name() : null, row.getPeriodNumber(),
                row.getStartTime(), row.getEndTime(), row.getSubjectName(), row.getTeacherId(),
                row.getSimultaneousGroup(), outcome, reason, resolvedClassId, resolvedSectionId,
                concern, concernReason);
    }

    /** {@code simultaneousGroupConcern} deliberately does not live here — see the post-pass in
     *  {@link #classifyInternal} for why it can only be judged after the whole batch is classified. */
    private record Classification(
            AdoptionOutcome outcome, String reason, Long resolvedClassId, Long resolvedSectionId, String groupTag) {

        static Classification invalid(String reason) {
            return new Classification(AdoptionOutcome.INVALID_OR_CONFLICTING, reason, null, null, null);
        }

        static Classification requiresConfirmation(String reason, Long classId, Long sectionId) {
            return new Classification(AdoptionOutcome.REQUIRES_ADMIN_CONFIRMATION, reason, classId, sectionId, null);
        }

        static Classification safe(Long classId, Long sectionId, String groupTag) {
            return new Classification(AdoptionOutcome.SAFE,
                    "Resolves cleanly to class/section/teacher; no conflicts detected.",
                    classId, sectionId, groupTag);
        }
    }

    static final class Counters {
        int scanned;
        int safe;
        int requiresAdminConfirmation;
        int invalidOrConflicting;
        int alreadyAdopted;
        int skipped;
        int adopted;
        int unresolvedClassMappings;
        int unresolvedSectionMappings;
        int invalidOrIneligibleTeachers;
        int slotConflicts;
        int teacherOverlaps;
        int simultaneousGroupConcerns;
    }

    record ClassificationResult(Counters counters, List<TimetableCandidate> details) {}
}
