package com.indraacademy.ias_management.dto;

import java.util.List;

public class TimetableBulkImportDtos {

    /** One row that failed validation or was rejected (e.g. slot already taken). */
    public record RowError(int row, String label, String reason) {}

    /** One period successfully created. */
    public record RowSuccess(int row, String label, Long entryId) {}

    public record Result(int totalRows, int successful, int failed,
                          List<RowError> errors, List<RowSuccess> created) {}

    private TimetableBulkImportDtos() {}
}
