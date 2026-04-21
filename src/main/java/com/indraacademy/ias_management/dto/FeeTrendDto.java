package com.indraacademy.ias_management.dto;

public class FeeTrendDto {
    private String month;
    private long amount;

    public FeeTrendDto(String month, long amount) {
        this.month = month;
        this.amount = amount;
    }

    public String getMonth() { return month; }
    public long getAmount() { return amount; }
}
