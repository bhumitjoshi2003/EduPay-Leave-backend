package com.indraacademy.ias_management.dto;

import java.util.List;
import java.util.Map;

/**
 * Aggregated, ranked exam performance for one class's one exam.
 * Returned by GET /api/marks/class/{className}/exam-performance and embedded
 * (one per class) in GET /api/marks/school/performance-summary.
 */
public class ClassExamPerformanceDTO {

    private String className;
    private String examName;
    /** Null if no student has a mark entered yet. */
    private Double classAveragePercentage;
    /** Only students with a percentage, sorted descending — first = top scorer, last = lowest scorer. */
    private List<StudentScoreDTO> studentsRanked;
    private List<String> studentsWithNoMarksEntered;
    /** subjectName -> class average percentage for that subject. */
    private Map<String, Double> subjectAverages;

    public ClassExamPerformanceDTO(String className, String examName, Double classAveragePercentage,
                                   List<StudentScoreDTO> studentsRanked, List<String> studentsWithNoMarksEntered,
                                   Map<String, Double> subjectAverages) {
        this.className = className;
        this.examName = examName;
        this.classAveragePercentage = classAveragePercentage;
        this.studentsRanked = studentsRanked;
        this.studentsWithNoMarksEntered = studentsWithNoMarksEntered;
        this.subjectAverages = subjectAverages;
    }

    public String getClassName() { return className; }
    public String getExamName() { return examName; }
    public Double getClassAveragePercentage() { return classAveragePercentage; }
    public List<StudentScoreDTO> getStudentsRanked() { return studentsRanked; }
    public List<String> getStudentsWithNoMarksEntered() { return studentsWithNoMarksEntered; }
    public Map<String, Double> getSubjectAverages() { return subjectAverages; }

    public static class StudentScoreDTO {
        private String studentName;
        private Double percentage;
        private Integer rank;

        public StudentScoreDTO(String studentName, Double percentage, Integer rank) {
            this.studentName = studentName;
            this.percentage = percentage;
            this.rank = rank;
        }

        public String getStudentName() { return studentName; }
        public Double getPercentage() { return percentage; }
        public Integer getRank() { return rank; }
    }
}
