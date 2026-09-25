package com.indraacademy.ias_management.repository;

import java.time.LocalDate;

/** Attendance V2 aggregate projection: a class a student was marked in and their last date there. */
public record StudentLatestClass(String studentId, Long classId, LocalDate lastDate) {}
