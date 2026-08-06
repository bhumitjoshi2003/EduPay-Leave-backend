package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * Every active class's own latest exam performance, in one call.
 * Returned by GET /api/marks/school/performance-summary.
 *
 * classesWithNoExamConfigured and classesWithExamButNoMarksEntered are kept
 * separate deliberately — they mean different things ("nothing set up yet"
 * vs "set up but not graded yet") and collapsing them loses that distinction.
 */
public class SchoolPerformanceSummaryDTO {

    private String session;
    private List<ClassExamPerformanceDTO> classResults;
    private List<String> classesWithNoExamConfigured;
    private List<String> classesWithExamButNoMarksEntered;

    public SchoolPerformanceSummaryDTO(String session, List<ClassExamPerformanceDTO> classResults,
                                       List<String> classesWithNoExamConfigured,
                                       List<String> classesWithExamButNoMarksEntered) {
        this.session = session;
        this.classResults = classResults;
        this.classesWithNoExamConfigured = classesWithNoExamConfigured;
        this.classesWithExamButNoMarksEntered = classesWithExamButNoMarksEntered;
    }

    public String getSession() { return session; }
    public List<ClassExamPerformanceDTO> getClassResults() { return classResults; }
    public List<String> getClassesWithNoExamConfigured() { return classesWithNoExamConfigured; }
    public List<String> getClassesWithExamButNoMarksEntered() { return classesWithExamButNoMarksEntered; }
}
