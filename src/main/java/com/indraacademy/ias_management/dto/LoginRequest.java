package com.indraacademy.ias_management.dto;

/**
 * Request body for POST /api/auth/login.
 *
 * schoolSlug is optional — only sent by branded login pages.
 * When present the backend verifies the authenticating user belongs to that school.
 * When absent (generic login page, website) no school-scoping is applied.
 */
public class LoginRequest {

    private String userId;
    private String password;
    private String schoolSlug;   // nullable — only set on branded login pages

    public String getUserId()   { return userId;   }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSchoolSlug() { return schoolSlug; }
    public void setSchoolSlug(String schoolSlug) { this.schoolSlug = schoolSlug; }
}
