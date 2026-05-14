package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Single-row table for platform-wide subscription settings.
 * Super admin can edit these via the admin UI without a code deploy.
 */
@Entity
@Table(name = "global_subscription_config")
public class GlobalSubscriptionConfig {

    @Id
    @Column(name = "config_id")
    private Integer configId = 1;

    /** Days after subscription expiry before account is fully locked (default 15) */
    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays = 15;

    /** Default trial duration in days when a school is onboarded (default 30) */
    @Column(name = "default_trial_days", nullable = false)
    private int defaultTrialDays = 30;

    /** Days before expiry to send the warning notification (default 1) */
    @Column(name = "expiry_notify_days", nullable = false)
    private int expiryNotifyDays = 1;

    @Column(name = "updated_by_admin_id", length = 100)
    private String updatedByAdminId;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public GlobalSubscriptionConfig() {}

    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }

    public int getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }

    public int getDefaultTrialDays() { return defaultTrialDays; }
    public void setDefaultTrialDays(int defaultTrialDays) { this.defaultTrialDays = defaultTrialDays; }

    public int getExpiryNotifyDays() { return expiryNotifyDays; }
    public void setExpiryNotifyDays(int expiryNotifyDays) { this.expiryNotifyDays = expiryNotifyDays; }

    public String getUpdatedByAdminId() { return updatedByAdminId; }
    public void setUpdatedByAdminId(String updatedByAdminId) { this.updatedByAdminId = updatedByAdminId; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
