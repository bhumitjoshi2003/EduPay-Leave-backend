package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/auth/login.
 *
 * schoolSlug is optional — only sent by branded login pages.
 * When present the backend verifies the authenticating user belongs to that school.
 * When absent (generic login page, website) no school-scoping is applied.
 */
public class LoginRequest {

    @NotBlank(message = "User ID is required")
    @Size(max = 100, message = "User ID must not exceed 100 characters")
    private String userId;

    @NotBlank(message = "Password is required")
    @Size(max = 255, message = "Password must not exceed 255 characters")
    private String password;

    @Size(max = 100, message = "School slug must not exceed 100 characters")
    private String schoolSlug;   // nullable — only set on branded login pages

    public String getUserId()   { return userId;   }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSchoolSlug() { return schoolSlug; }
    public void setSchoolSlug(String schoolSlug) { this.schoolSlug = schoolSlug; }
}
