package com.indraacademy.ias_management.dto;

/** Client selects only a category — the server re-resolves eligible recipients itself and
 *  never accepts an explicit recipient list from the caller. */
public record StaffAdoptionReminderRequest(StaffAdoptionReminderType type) {
}
