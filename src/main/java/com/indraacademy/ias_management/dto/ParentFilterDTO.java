package com.indraacademy.ias_management.dto;

import lombok.Data;

@Data
public class ParentFilterDTO {

    private String search;

    /** ACTIVE or DISABLED. Null/blank means no status filter. */
    private String status;

    /** LINKED or UNLINKED (has at least one active student link). Null/blank means no filter. */
    private String linked;

    private Long schoolId;

    public String getSearch() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLinked() {
        return linked;
    }

    public void setLinked(String linked) {
        this.linked = linked;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(Long schoolId) {
        this.schoolId = schoolId;
    }
}
