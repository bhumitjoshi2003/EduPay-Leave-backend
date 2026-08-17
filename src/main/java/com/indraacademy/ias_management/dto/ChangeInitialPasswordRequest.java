package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for POST /api/auth/change-initial-password. Intentionally has no
 * userId field — the target account is always resolved from the caller's
 * restricted-session SecurityContext, never from client input.
 */
@Data
public class ChangeInitialPasswordRequest {

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 255, message = "New password must be between 8 and 255 characters")
    private String newPassword;

    @NotBlank(message = "Password confirmation is required")
    private String confirmPassword;
}
