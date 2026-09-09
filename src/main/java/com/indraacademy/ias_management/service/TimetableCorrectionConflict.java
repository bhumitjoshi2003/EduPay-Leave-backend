package com.indraacademy.ias_management.service;

import org.springframework.dao.DataIntegrityViolationException;

/** A correction conflict whose message is safe to show to the caller. */
public class TimetableCorrectionConflict extends DataIntegrityViolationException {
    public TimetableCorrectionConflict(String message) { super(message); }
}
