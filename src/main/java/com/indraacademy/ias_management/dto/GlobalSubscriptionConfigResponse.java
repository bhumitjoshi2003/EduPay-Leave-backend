package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.GlobalSubscriptionConfig;

import java.time.LocalDateTime;

public class GlobalSubscriptionConfigResponse {
    private int gracePeriodDays;
    private int defaultTrialDays;
    private int expiryNotifyDays;
    private String updatedByAdminId;
    private LocalDateTime updatedAt;

    public static GlobalSubscriptionConfigResponse from(GlobalSubscriptionConfig c) {
        GlobalSubscriptionConfigResponse r = new GlobalSubscriptionConfigResponse();
        r.gracePeriodDays   = c.getGracePeriodDays();
        r.defaultTrialDays  = c.getDefaultTrialDays();
        r.expiryNotifyDays  = c.getExpiryNotifyDays();
        r.updatedByAdminId  = c.getUpdatedByAdminId();
        r.updatedAt         = c.getUpdatedAt();
        return r;
    }

    public int getGracePeriodDays() { return gracePeriodDays; }
    public int getDefaultTrialDays() { return defaultTrialDays; }
    public int getExpiryNotifyDays() { return expiryNotifyDays; }
    public String getUpdatedByAdminId() { return updatedByAdminId; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
