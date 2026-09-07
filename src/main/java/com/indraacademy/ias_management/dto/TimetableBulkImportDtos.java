package com.indraacademy.ias_management.dto;

import java.util.List;

public class TimetableBulkImportDtos {

    /** One row that failed validation or was rejected (e.g. slot already taken). */
    public record RowError(int row, String label, String reason) {}

    /** One period successfully created. */
    public record RowSuccess(int row, String label, Long entryId) {}

    /** {@code academicSessionId} is echoed on every result so an admin reviewing the report can
     *  never mistake which session/year the import targeted — see TimetableBulkImportService. */
    public record Result(Long academicSessionId, int totalRows, int successful, int failed,
                          List<RowError> errors, List<RowSuccess> created) {}

    private TimetableBulkImportDtos() {}
}
