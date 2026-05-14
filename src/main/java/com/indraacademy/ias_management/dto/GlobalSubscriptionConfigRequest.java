package com.indraacademy.ias_management.dto;

public class GlobalSubscriptionConfigRequest {
    private Integer gracePeriodDays;
    private Integer defaultTrialDays;
    private Integer expiryNotifyDays;

    public Integer getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(Integer gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }

    public Integer getDefaultTrialDays() { return defaultTrialDays; }
    public void setDefaultTrialDays(Integer defaultTrialDays) { this.defaultTrialDays = defaultTrialDays; }

    public Integer getExpiryNotifyDays() { return expiryNotifyDays; }
    public void setExpiryNotifyDays(Integer expiryNotifyDays) { this.expiryNotifyDays = expiryNotifyDays; }
}
