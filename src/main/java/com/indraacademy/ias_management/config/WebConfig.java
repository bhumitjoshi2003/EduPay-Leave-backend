package com.indraacademy.ias_management.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AiCopilotEntitlementInterceptor aiCopilotEntitlementInterceptor;

    @Autowired
    public WebConfig(AiCopilotEntitlementInterceptor aiCopilotEntitlementInterceptor) {
        this.aiCopilotEntitlementInterceptor = aiCopilotEntitlementInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(aiCopilotEntitlementInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/public/**", "/api/auth/**", "/api/super-admin/**");
    }

    // Phase 3: the local-disk static resource handlers for /api/uploads/events/images/** and
    // /api/uploads/school-logos/** were removed once every persistent upload category
    // (including the one meaningful legacy asset, the school logo) fully migrated to Neon
    // Object Storage — see ObjectStorageService.resolveDisplayUrl for how those categories are
    // served now (a short-lived presigned GET URL, generated per read, never a static file
    // path). FileStorageProperties (image.upload.directory) had no other purpose and was
    // removed alongside this.

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Spring's default async request timeout (30s) is too short for a streamed AI
        // reply that may involve several tool-calling round trips before the model
        // starts producing the final answer — see AiProxyController's StreamingResponseBody
        // endpoint. Without this, a slow-but-healthy stream gets killed mid-response.
        configurer.setDefaultTimeout(120_000);
    }
}
