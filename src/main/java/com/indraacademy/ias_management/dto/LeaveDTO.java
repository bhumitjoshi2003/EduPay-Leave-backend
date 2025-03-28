package com.indraacademy.ias_management.dto;

import lombok.Data;

@Data
public class LeaveDTO {
    private String studentId;
    private String leaveDate;
    private String className;
    private String reason;

    public LeaveDTO(){}
}