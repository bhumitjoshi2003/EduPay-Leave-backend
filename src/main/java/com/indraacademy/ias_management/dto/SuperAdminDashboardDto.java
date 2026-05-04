package com.indraacademy.ias_management.dto;

/** Platform-wide stats shown on the SUPER_ADMIN dashboard. */
public class SuperAdminDashboardDto {

    private long totalSchools;
    private long activeSchools;
    private long totalStudents;
    private long totalTeachers;
    private long revenueThisMonth;

    public SuperAdminDashboardDto(long totalSchools, long activeSchools,
                                   long totalStudents, long totalTeachers,
                                   long revenueThisMonth) {
        this.totalSchools = totalSchools;
        this.activeSchools = activeSchools;
        this.totalStudents = totalStudents;
        this.totalTeachers = totalTeachers;
        this.revenueThisMonth = revenueThisMonth;
    }

    public long getTotalSchools() { return totalSchools; }
    public long getActiveSchools() { return activeSchools; }
    public long getTotalStudents() { return totalStudents; }
    public long getTotalTeachers() { return totalTeachers; }
    public long getRevenueThisMonth() { return revenueThisMonth; }
}
