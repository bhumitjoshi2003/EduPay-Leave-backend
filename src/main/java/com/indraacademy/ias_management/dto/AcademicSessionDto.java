package com.indraacademy.ias_management.dto;

import java.time.LocalDate;

public class AcademicSessionDto {
    private Long id;
    private String label;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean current;

    public AcademicSessionDto() {}

    public AcademicSessionDto(Long id, String label, LocalDate startDate, LocalDate endDate, boolean current) {
        this.id = id;
        this.label = label;
        this.startDate = startDate;
        this.endDate = endDate;
        this.current = current;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
}
