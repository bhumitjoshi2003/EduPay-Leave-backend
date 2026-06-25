package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PublishRequest {

    @NotNull  private Long   templateId;
    @NotBlank private String session;
    @NotBlank private String className;

    public Long   getTemplateId()              { return templateId; }
    public void   setTemplateId(Long id)       { this.templateId = id; }
    public String getSession()                 { return session; }
    public void   setSession(String session)   { this.session = session; }
    public String getClassName()               { return className; }
    public void   setClassName(String cn)      { this.className = cn; }
}
