package com.indraacademy.ias_management.dto;

import java.util.List;

/** Full weighted result for a single student in an assessment group.
 *  Used by both the student-facing report card and the class-level results view. */
public class WeightedGroupResultDTO {

    private Long groupId;
    private String groupName;
    private String groupType;

    /** Final weighted percentage (e.g. 72.35). */
    private double weightedPercentage;

    /** Per-subject weighted percentage breakdown. */
    private List<SubjectWeightedResultDTO> subjectResults;

    /** Per-exam contribution breakdown. Non-null only when groupType = EXAM_BASED. */
    private List<ExamBreakdownDTO> examBreakdowns;

    /** Per-child-group contribution breakdown. Non-null only when groupType = GROUP_BASED. */
    private List<GroupBreakdownDTO> groupBreakdowns;

    /** Competition rank within the class (1 + count of students with higher score). 0 = not yet computed. */
    private int rank;

    /** Rich marks table for report card rendering. Populated for EXAM_BASED groups.
     *  Contains per-subject per-exam marks so the report card can show a column per exam. */
    private MarksTableDTO marksTable;

    public WeightedGroupResultDTO(Long groupId, String groupName, String groupType,
                                   double weightedPercentage,
                                   List<SubjectWeightedResultDTO> subjectResults,
                                   List<ExamBreakdownDTO> examBreakdowns,
                                   List<GroupBreakdownDTO> groupBreakdowns,
                                   MarksTableDTO marksTable,
                                   int rank) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.groupType = groupType;
        this.weightedPercentage = weightedPercentage;
        this.subjectResults = subjectResults;
        this.examBreakdowns = examBreakdowns;
        this.groupBreakdowns = groupBreakdowns;
        this.marksTable = marksTable;
        this.rank = rank;
    }

    public Long getGroupId() { return groupId; }
    public String getGroupName() { return groupName; }
    public String getGroupType() { return groupType; }
    public double getWeightedPercentage() { return weightedPercentage; }
    public List<SubjectWeightedResultDTO> getSubjectResults() { return subjectResults; }
    public List<ExamBreakdownDTO> getExamBreakdowns() { return examBreakdowns; }
    public List<GroupBreakdownDTO> getGroupBreakdowns() { return groupBreakdowns; }
    public MarksTableDTO getMarksTable() { return marksTable; }
    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    // ── Nested: SubjectWeightedResultDTO ──────────────────────────────

    public static class SubjectWeightedResultDTO {
        private String subjectName;
        private double weightedPercentage;

        public SubjectWeightedResultDTO(String subjectName, double weightedPercentage) {
            this.subjectName = subjectName;
            this.weightedPercentage = weightedPercentage;
        }

        public String getSubjectName() { return subjectName; }
        public double getWeightedPercentage() { return weightedPercentage; }
    }

    // ── Nested: ExamBreakdownDTO ───────────────────────────────────────

    public static class ExamBreakdownDTO {
        private Long examId;
        private String examName;
        private double obtained;
        private double max;
        private double percentage;
        private double weightage;
        private double contribution; // percentage * weightage

        public ExamBreakdownDTO(Long examId, String examName, double obtained, double max,
                                 double percentage, double weightage, double contribution) {
            this.examId = examId;
            this.examName = examName;
            this.obtained = obtained;
            this.max = max;
            this.percentage = percentage;
            this.weightage = weightage;
            this.contribution = contribution;
        }

        public Long getExamId() { return examId; }
        public String getExamName() { return examName; }
        public double getObtained() { return obtained; }
        public double getMax() { return max; }
        public double getPercentage() { return percentage; }
        public double getWeightage() { return weightage; }
        public double getContribution() { return contribution; }
    }

    // ── Nested: GroupBreakdownDTO ──────────────────────────────────────

    public static class GroupBreakdownDTO {
        private Long groupId;
        private String groupName;
        private double percentage;
        private double weightage;
        private double contribution;

        public GroupBreakdownDTO(Long groupId, String groupName,
                                  double percentage, double weightage, double contribution) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.percentage = percentage;
            this.weightage = weightage;
            this.contribution = contribution;
        }

        public Long getGroupId() { return groupId; }
        public String getGroupName() { return groupName; }
        public double getPercentage() { return percentage; }
        public double getWeightage() { return weightage; }
        public double getContribution() { return contribution; }
    }

    // ── Nested: MarksTableDTO (rich per-subject per-exam table for report card) ──

    public static class MarksTableDTO {
        /** One column per exam in the group (EXAM_BASED) or per child group (GROUP_BASED). */
        private List<ExamColumnDTO> examColumns;
        /** One row per subject. */
        private List<SubjectRowDTO> subjectRows;
        /** Totals row — obtained / max per exam column. */
        private List<ExamTotalDTO> examTotals;

        public MarksTableDTO(List<ExamColumnDTO> examColumns,
                             List<SubjectRowDTO> subjectRows,
                             List<ExamTotalDTO> examTotals) {
            this.examColumns = examColumns;
            this.subjectRows = subjectRows;
            this.examTotals = examTotals;
        }

        public List<ExamColumnDTO> getExamColumns() { return examColumns; }
        public List<SubjectRowDTO> getSubjectRows() { return subjectRows; }
        public List<ExamTotalDTO> getExamTotals() { return examTotals; }

        public static class ExamColumnDTO {
            private Long examId;
            private String examName;
            private double maxTotal;
            private double weightage;

            public ExamColumnDTO(Long examId, String examName, double maxTotal, double weightage) {
                this.examId = examId; this.examName = examName;
                this.maxTotal = maxTotal; this.weightage = weightage;
            }
            public Long getExamId() { return examId; }
            public String getExamName() { return examName; }
            public double getMaxTotal() { return maxTotal; }
            public double getWeightage() { return weightage; }
        }

        public static class SubjectRowDTO {
            private String subjectName;
            /** One entry per exam column. Null if this subject doesn't exist in that exam. */
            private List<SubjectExamMarkDTO> examMarks;
            /** Final weighted percentage for this subject across all exams. */
            private double weightedPercentage;

            public SubjectRowDTO(String subjectName, List<SubjectExamMarkDTO> examMarks,
                                  double weightedPercentage) {
                this.subjectName = subjectName;
                this.examMarks = examMarks;
                this.weightedPercentage = weightedPercentage;
            }
            public String getSubjectName() { return subjectName; }
            public List<SubjectExamMarkDTO> getExamMarks() { return examMarks; }
            public double getWeightedPercentage() { return weightedPercentage; }
        }

        public static class SubjectExamMarkDTO {
            /** Null if student was absent. */
            private Double obtained;
            private double max;
            private double percentage;

            public SubjectExamMarkDTO(Double obtained, double max, double percentage) {
                this.obtained = obtained; this.max = max; this.percentage = percentage;
            }
            public Double getObtained() { return obtained; }
            public double getMax() { return max; }
            public double getPercentage() { return percentage; }
        }

        public static class ExamTotalDTO {
            private double obtained;
            private double max;

            public ExamTotalDTO(double obtained, double max) {
                this.obtained = obtained; this.max = max;
            }
            public double getObtained() { return obtained; }
            public double getMax() { return max; }
        }
    }

    // ── Nested: StudentGroupResultDTO (for class-level results list) ───

    public static class StudentGroupResultDTO {
        private String studentId;
        private String studentName;
        private double weightedPercentage;
        private int rank;

        public StudentGroupResultDTO(String studentId, String studentName,
                                      double weightedPercentage, int rank) {
            this.studentId = studentId;
            this.studentName = studentName;
            this.weightedPercentage = weightedPercentage;
            this.rank = rank;
        }

        public String getStudentId() { return studentId; }
        public String getStudentName() { return studentName; }
        public double getWeightedPercentage() { return weightedPercentage; }
        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }
    }
}
