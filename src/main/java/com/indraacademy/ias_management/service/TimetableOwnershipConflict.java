package com.indraacademy.ias_management.service;

import org.springframework.dao.DataIntegrityViolationException;

public class TimetableOwnershipConflict extends DataIntegrityViolationException {
    private final Long timetableEntryId;
    public TimetableOwnershipConflict(Long id, String subject) {
        super(subject + " is already scheduled for this period and is assigned to another teacher.");
        timetableEntryId = id;
    }
    public record Body(String code, String message, Long timetableEntryId) {}
    public Body body() {
        return new Body("SAME_SUBJECT_ASSIGNED_TO_ANOTHER_TEACHER", getMessage(), timetableEntryId);
    }
}
