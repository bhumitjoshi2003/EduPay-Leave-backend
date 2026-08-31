package com.indraacademy.ias_management.dto;

import java.util.List;
import java.util.Map;

/**
 * Shapes for Parent Bulk Import — a two-call, fully stateless flow (no persisted staging
 * table): {@code /preview} parses + validates + matches every row and returns a full
 * per-row picture with nothing written to the database; {@code /confirm} re-parses and
 * re-validates the SAME uploaded file from scratch (it never trusts a client-cached preview
 * as authoritative) plus the admin's resolutions for any ambiguous rows, and only then
 * creates accounts/relationships. This mirrors the required Upload → Validate → Detect
 * Matches → Preview → Resolve Ambiguous → Confirm → Create workflow without needing a new
 * persisted batch/row table — there is no external async actor here (unlike the AI
 * workflows this codebase already has staged-batch tables for), so nothing needs to survive
 * between requests on the server; the admin's browser holds the preview during review.
 */
public final class ParentBulkImportDtos {
    private ParentBulkImportDtos() {}

    public enum RowStatus {
        /** No match found in this school; a brand-new Parent will be created. */
        VALID_NEW_PARENT,
        /** Same normalized phone AND email as an existing Parent in this school — safe
         *  auto-match, only a new relationship is created, never a duplicate Parent. */
        VALID_EXISTING_PARENT_MATCH,
        /** Phone matches an existing Parent but email differs (or one side is blank) —
         *  never auto-merged; admin must explicitly resolve. */
        CONFLICT_PHONE_MATCH_EMAIL_DIFFERS,
        /** Email matches an existing Parent but phone differs (or one side is blank) —
         *  never auto-merged; admin must explicitly resolve. */
        CONFLICT_EMAIL_MATCH_PHONE_DIFFERS,
        /** Student ID doesn't resolve to a student in the admin's own school (covers both
         *  "doesn't exist at all" and "belongs to a different school" — deliberately
         *  indistinguishable, so this never leaks whether an ID exists elsewhere). */
        INVALID_STUDENT_ID,
        /** Student exists in this school but has an exit status (graduated/transferred/
         *  withdrawn) — parent access cannot be granted to an exited student. */
        STUDENT_EXITED,
        /** Required field (parent name, phone, student ID, or relationship) is blank. */
        MISSING_REQUIRED_FIELD,
        /** Exact duplicate of another row already seen earlier in this same file. */
        DUPLICATE_ROW_IN_FILE,
        /** This exact parent-student relationship already exists (matched parent is
         *  already linked to this exact student) — nothing new to create. */
        ALREADY_LINKED
    }

    public record RowPreview(
            int row,
            String parentName, String phone, String email, String studentId, String relationship,
            RowStatus status, String message,
            String studentName, String className,
            /** Set only for VALID_EXISTING_PARENT_MATCH / the CONFLICT_* statuses — the
             *  existing Parent this row was compared against. */
            String matchedParentId,
            /** Groups rows in THIS file that share the same normalized phone+email —
             *  siblings of one new parent. Null when the row matched an existing parent
             *  instead (matchedParentId is used for that grouping there). */
            Integer siblingGroup) {}

    public record PreviewResponse(int totalRows, List<RowPreview> rows,
                                  int newParentCount, int existingParentMatchCount,
                                  int conflictCount, int invalidCount, int duplicateCount) {}

    public enum RowAction { CREATE_NEW, LINK_EXISTING, SKIP }

    /** Admin's explicit resolution for one ambiguous row, keyed by row number in the confirm
     *  request. Rows not present here that were flagged CONFLICT, INVALID, or STUDENT_EXITED
     *  at preview time are skipped — never silently guessed. */
    public record RowResolution(RowAction action, String existingParentId) {}

    public record ConfirmRequest(Map<Integer, RowResolution> resolutions) {}

    public record ConfirmedRow(int row, String parentId, String studentId, String outcome) {}

    public record ConfirmResponse(int totalRows, int parentsCreated, int relationshipsCreated,
                                  int skipped, List<ConfirmedRow> created, List<RowPreview> skippedRows) {}
}
