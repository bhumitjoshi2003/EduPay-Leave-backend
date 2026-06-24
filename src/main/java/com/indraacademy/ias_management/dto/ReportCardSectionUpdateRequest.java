package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Request body for bulk-updating all sections of a template in one call. */
public class ReportCardSectionUpdateRequest {

    @NotNull
    private List<SectionItem> sections;

    public List<SectionItem> getSections() { return sections; }
    public void setSections(List<SectionItem> sections) { this.sections = sections; }

    public static class SectionItem {

        @NotBlank(message = "sectionType is required")
        private String sectionType;

        private Boolean enabled = true;

        @NotNull(message = "displayOrder is required")
        private Integer displayOrder;

        /** Optional JSON config string for the section. */
        private String configJson;

        public String getSectionType() { return sectionType; }
        public void setSectionType(String sectionType) { this.sectionType = sectionType; }

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }

        public Integer getDisplayOrder() { return displayOrder; }
        public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

        public String getConfigJson() { return configJson; }
        public void setConfigJson(String configJson) { this.configJson = configJson; }
    }
}
