package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core weightage calculation engine.
 *
 * Supports two group types:
 *   EXAM_BASED  — leaf groups that directly contain exam configs with weightage
 *                 (e.g. Term 1 = UT1 20% + Half Yearly 80%)
 *   GROUP_BASED — composite groups that reference child groups with weightage
 *                 (e.g. Annual = Term 1 50% + Term 2 50%)
 *
 * Computation is recursive: GROUP_BASED groups delegate to EXAM_BASED children.
 *
 * Weighted % algorithm (EXAM_BASED):
 *   For each exam in the group:
 *     exam_pct = sum(obtained) / sum(max) * 100   (normalised, avoids scale bias)
 *   weighted_pct = Σ (exam_pct * exam_weightage)
 */
@Service
public class WeightageCalculationEngine {

    private static final Logger log = LoggerFactory.getLogger(WeightageCalculationEngine.class);

    @Autowired private AssessmentGroupRepository groupRepo;
    @Autowired private AssessmentGroupExamMappingRepository mappingRepo;
    @Autowired private AssessmentGroupCompositionRepository compositionRepo;
    @Autowired private AssessmentGroupResultRepository resultRepo;
    @Autowired private ExamConfigRepository examConfigRepo;
    @Autowired private ExamSubjectEntryRepository subjectEntryRepo;
    @Autowired private StudentMarkRepository markRepo;
    @Autowired private SecurityUtil securityUtil;

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Compute the weighted result for a single student in an assessment group.
     * schoolId is taken from SecurityUtil (thread-local from JWT).
     */
    @Transactional(readOnly = true)
    public WeightedGroupResultDTO computeForStudent(String studentId, Long groupId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(groupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + groupId));
        log.info("Computing weighted result for student={} group={} session={}", studentId, groupId, session);
        return compute(studentId, group, session, schoolId, 0);
    }

    /**
     * Compute weighted results for all students in a class for a given group,
     * assign competition ranks, and persist to assessment_group_result.
     * Idempotent — upserts on unique(student_id, group_id, session).
     */
    @Transactional
    public List<StudentGroupResultDTO> computeAndRankForClass(
            List<String> studentIds, Map<String, String> studentNames,
            Long groupId, String session) {

        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(groupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + groupId));

        log.info("Computing class results for group={} session={} students={}", groupId, session, studentIds.size());

        // Compute weighted % for each student
        List<StudentGroupResultDTO> results = new ArrayList<>();
        for (String studentId : studentIds) {
            try {
                WeightedGroupResultDTO r = compute(studentId, group, session, schoolId, 0);
                results.add(new StudentGroupResultDTO(studentId, studentNames.getOrDefault(studentId, ""),
                        r.getWeightedPercentage(), 0));
            } catch (Exception e) {
                log.warn("Failed to compute result for student={}: {}", studentId, e.getMessage());
                results.add(new StudentGroupResultDTO(studentId, studentNames.getOrDefault(studentId, ""), 0.0, 0));
            }
        }

        // Assign competition ranks: rank = 1 + count(students with strictly higher score)
        for (StudentGroupResultDTO r : results) {
            int rank = 1 + (int) results.stream()
                    .filter(other -> other.getWeightedPercentage() > r.getWeightedPercentage())
                    .count();
            r.setRank(rank);
        }

        // Upsert into assessment_group_result
        for (StudentGroupResultDTO r : results) {
            AssessmentGroupResult entity = resultRepo
                    .findByStudentIdAndAssessmentGroupIdAndSession(r.getStudentId(), groupId, session)
                    .orElseGet(AssessmentGroupResult::new);
            entity.setSchoolId(schoolId);
            entity.setStudentId(r.getStudentId());
            entity.setAssessmentGroupId(groupId);
            entity.setSession(session);
            entity.setWeightedScore(BigDecimal.valueOf(r.getWeightedPercentage()));
            entity.setRankPosition(r.getRank());
            entity.setComputedAt(LocalDateTime.now());
            resultRepo.save(entity);
        }

        return results;
    }

    // ── Private computation ────────────────────────────────────────────

    private WeightedGroupResultDTO compute(String studentId, AssessmentGroup group,
                                            String session, Long schoolId, int depth) {
        if (depth > 5) {
            throw new IllegalStateException("Assessment group cycle detected at group: " + group.getId());
        }

        if ("EXAM_BASED".equals(group.getGroupType())) {
            return computeExamBased(studentId, group, session, schoolId);
        } else {
            return computeGroupBased(studentId, group, session, schoolId, depth);
        }
    }

    private WeightedGroupResultDTO computeExamBased(String studentId, AssessmentGroup group,
                                                      String session, Long schoolId) {
        List<AssessmentGroupExamMapping> mappings =
                mappingRepo.findByAssessmentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId);

        if (mappings.isEmpty()) {
            return emptyResult(group);
        }

        // Batch: load all subject entries for all exams in this group
        List<Long> examConfigIds = mappings.stream()
                .map(AssessmentGroupExamMapping::getExamConfigId)
                .collect(Collectors.toList());

        List<ExamSubjectEntry> allSubjects =
                subjectEntryRepo.findByExamConfigIdInAndSchoolId(examConfigIds, schoolId);

        List<Long> allSubjectEntryIds = allSubjects.stream()
                .map(ExamSubjectEntry::getId)
                .collect(Collectors.toList());

        // Batch: load all marks for this student across all subject entries
        Map<Long, StudentMark> markBySubjectEntryId = Collections.emptyMap();
        Set<Long> studentSubjectEntryIds = Collections.emptySet();
        if (!allSubjectEntryIds.isEmpty()) {
            List<StudentMark> marks = markRepo.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(
                    studentId, allSubjectEntryIds, schoolId);
            markBySubjectEntryId = marks.stream()
                    .collect(Collectors.toMap(StudentMark::getExamSubjectEntryId, m -> m));
            // Track which subject entries this student has any mark record for.
            // If a student never took an elective, no mark record exists at all → exclude it.
            studentSubjectEntryIds = marks.stream()
                    .map(StudentMark::getExamSubjectEntryId)
                    .collect(Collectors.toSet());
        }
        final Set<Long> studentEntryIds = studentSubjectEntryIds;

        // Derive the set of subject NAMES this student is enrolled in (has any mark record).
        // This filters out elective subjects the student didn't choose.
        final Set<String> studentEnrolledSubjects = allSubjects.stream()
                .filter(e -> studentEntryIds.contains(e.getId()))
                .map(ExamSubjectEntry::getSubjectName)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        // Group subjects by examConfigId
        Map<Long, List<ExamSubjectEntry>> subjectsByExam = allSubjects.stream()
                .collect(Collectors.groupingBy(ExamSubjectEntry::getExamConfigId));

        // Build per-exam breakdowns and accumulate subject weighted percentages
        List<ExamBreakdownDTO> examBreakdowns = new ArrayList<>();
        Map<String, Double> subjectWeightedPcts = new LinkedHashMap<>();
        double totalWeightedPct = 0.0;

        // MarksTable tracking structures
        List<ExamConfig> orderedExams = new ArrayList<>();
        Map<Long, double[]> examObtainedMaxByExam = new LinkedHashMap<>(); // [obtained, max]
        Map<Long, Map<String, MarksTableDTO.SubjectExamMarkDTO>> perExamPerSubjectMarks = new LinkedHashMap<>();
        Set<String> orderedSubjectNames = new LinkedHashSet<>();

        for (AssessmentGroupExamMapping mapping : mappings) {
            Optional<ExamConfig> examOpt = examConfigRepo.findById(mapping.getExamConfigId());
            if (examOpt.isEmpty() || !schoolId.equals(examOpt.get().getSchoolId())) continue;
            ExamConfig exam = examOpt.get();

            List<ExamSubjectEntry> subjects = subjectsByExam.getOrDefault(exam.getId(), Collections.emptyList());
            if (subjects.isEmpty()) continue;

            orderedExams.add(exam);
            Map<String, MarksTableDTO.SubjectExamMarkDTO> subjectMarkMap = new LinkedHashMap<>();
            double examObtained = 0.0;
            double examMax = 0.0;

            for (ExamSubjectEntry subject : subjects) {
                // Skip subjects the student didn't enrol in (no mark record across any exam).
                // Core subjects always have mark records; electives not chosen by the student don't.
                if (!studentEnrolledSubjects.contains(subject.getSubjectName())) continue;

                StudentMark mark = markBySubjectEntryId.get(subject.getId());
                Double obtained = (mark != null) ? mark.getMarksObtained() : null;
                double obtainedVal = (obtained != null) ? obtained : 0.0;
                examObtained += obtainedVal;
                examMax += subject.getMaxMarks();

                // Per-subject weighted contribution
                double subjectPct = subject.getMaxMarks() > 0
                        ? (obtainedVal / subject.getMaxMarks()) * 100.0 : 0.0;
                double subjectContrib = subjectPct * mapping.getWeightage().doubleValue();
                subjectWeightedPcts.merge(subject.getSubjectName(), subjectContrib, Double::sum);

                // MarksTable: record per-subject per-exam mark (null obtained = absent)
                double markPct = subject.getMaxMarks() > 0
                        ? (obtainedVal / subject.getMaxMarks()) * 100.0 : 0.0;
                subjectMarkMap.put(subject.getSubjectName(),
                        new MarksTableDTO.SubjectExamMarkDTO(obtained, subject.getMaxMarks(), markPct));
                orderedSubjectNames.add(subject.getSubjectName());
            }

            double examPct = examMax > 0 ? (examObtained / examMax * 100.0) : 0.0;
            double weight = mapping.getWeightage().doubleValue();
            double contribution = examPct * weight;
            totalWeightedPct += contribution;

            examBreakdowns.add(new ExamBreakdownDTO(
                    exam.getId(), exam.getExamName(),
                    examObtained, examMax, examPct, weight, contribution));

            examObtainedMaxByExam.put(exam.getId(), new double[]{examObtained, examMax});
            perExamPerSubjectMarks.put(exam.getId(), subjectMarkMap);
        }

        List<SubjectWeightedResultDTO> subjectResults = subjectWeightedPcts.entrySet().stream()
                .map(e -> new SubjectWeightedResultDTO(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        // Build MarksTableDTO
        List<MarksTableDTO.ExamColumnDTO> examColumns = new ArrayList<>();
        for (ExamConfig exam : orderedExams) {
            double maxTotal = subjectsByExam.getOrDefault(exam.getId(), Collections.emptyList())
                    .stream().mapToDouble(ExamSubjectEntry::getMaxMarks).sum();
            double w = mappings.stream()
                    .filter(m -> m.getExamConfigId().equals(exam.getId()))
                    .findFirst().map(m -> m.getWeightage().doubleValue()).orElse(0.0);
            examColumns.add(new MarksTableDTO.ExamColumnDTO(exam.getId(), exam.getExamName(), maxTotal, w));
        }

        List<MarksTableDTO.SubjectRowDTO> subjectRows = new ArrayList<>();
        for (String subjectName : orderedSubjectNames) {
            List<MarksTableDTO.SubjectExamMarkDTO> examMarks = new ArrayList<>();
            for (ExamConfig exam : orderedExams) {
                Map<String, MarksTableDTO.SubjectExamMarkDTO> subjectMap =
                        perExamPerSubjectMarks.getOrDefault(exam.getId(), Collections.emptyMap());
                examMarks.add(subjectMap.get(subjectName)); // null if subject not in this exam
            }
            subjectRows.add(new MarksTableDTO.SubjectRowDTO(
                    subjectName, examMarks, subjectWeightedPcts.getOrDefault(subjectName, 0.0)));
        }

        List<MarksTableDTO.ExamTotalDTO> examTotals = new ArrayList<>();
        for (ExamConfig exam : orderedExams) {
            double[] om = examObtainedMaxByExam.getOrDefault(exam.getId(), new double[]{0.0, 0.0});
            examTotals.add(new MarksTableDTO.ExamTotalDTO(om[0], om[1]));
        }

        MarksTableDTO marksTable = new MarksTableDTO(examColumns, subjectRows, examTotals);

        return new WeightedGroupResultDTO(
                group.getId(), group.getName(), group.getGroupType(),
                totalWeightedPct, subjectResults, examBreakdowns, null, marksTable, 0);
    }

    private WeightedGroupResultDTO computeGroupBased(String studentId, AssessmentGroup group,
                                                      String session, Long schoolId, int depth) {
        List<AssessmentGroupComposition> compositions =
                compositionRepo.findByParentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId);

        if (compositions.isEmpty()) {
            return emptyResult(group);
        }

        List<GroupBreakdownDTO> groupBreakdowns = new ArrayList<>();
        double totalWeightedPct = 0.0;

        for (AssessmentGroupComposition comp : compositions) {
            AssessmentGroup childGroup = groupRepo.findByIdAndSchoolId(comp.getChildGroupId(), schoolId)
                    .orElse(null);
            if (childGroup == null) continue;

            WeightedGroupResultDTO childResult = compute(studentId, childGroup, session, schoolId, depth + 1);
            double weight = comp.getWeightage().doubleValue();
            double contribution = childResult.getWeightedPercentage() * weight;
            totalWeightedPct += contribution;

            groupBreakdowns.add(new GroupBreakdownDTO(
                    childGroup.getId(), childGroup.getName(),
                    childResult.getWeightedPercentage(), weight, contribution));
        }

        return new WeightedGroupResultDTO(
                group.getId(), group.getName(), group.getGroupType(),
                totalWeightedPct, Collections.emptyList(), null, groupBreakdowns, null, 0);
    }

    private WeightedGroupResultDTO emptyResult(AssessmentGroup group) {
        return new WeightedGroupResultDTO(
                group.getId(), group.getName(), group.getGroupType(),
                0.0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, 0);
    }
}
