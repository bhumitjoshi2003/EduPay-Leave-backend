package com.indraacademy.ias_management.dto;

public class NotificationChannelDTO {

    private Long id;
    private String channelType;
    private boolean enabled;
    private String configJson;

    public NotificationChannelDTO() {}

    public NotificationChannelDTO(Long id, String channelType, boolean enabled, String configJson) {
        this.id = id;
        this.channelType = channelType;
        this.enabled = enabled;
        this.configJson = configJson;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
