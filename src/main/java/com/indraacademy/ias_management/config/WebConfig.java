package com.indraacademy.ias_management.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FileStorageProperties fileStorageProperties;
    private final AiCopilotEntitlementInterceptor aiCopilotEntitlementInterceptor;

    @Value("${student.photo.directory:./uploads/student-photos}")
    private String studentPhotoDirectory;

    @Value("${teacher.photo.directory:./uploads/teacher-photos}")
    private String teacherPhotoDirectory;

    @Value("${admin.photo.directory:./uploads/admin-photos}")
    private String adminPhotoDirectory;

    @Value("${school.logo.directory:./uploads/school-logos}")
    private String schoolLogoDirectory;

    @Autowired
    public WebConfig(FileStorageProperties fileStorageProperties,
                     AiCopilotEntitlementInterceptor aiCopilotEntitlementInterceptor) {
        this.fileStorageProperties = fileStorageProperties;
        this.aiCopilotEntitlementInterceptor = aiCopilotEntitlementInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(aiCopilotEntitlementInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/public/**", "/api/auth/**", "/api/super-admin/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String eventsPath = Paths.get(fileStorageProperties.getDirectory()).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/uploads/events/images/**")
                .addResourceLocations("file:" + eventsPath + "/");

        String studentPhotosPath = Paths.get(studentPhotoDirectory).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/uploads/student-photos/**")
                .addResourceLocations("file:" + studentPhotosPath + "/");

        String teacherPhotosPath = Paths.get(teacherPhotoDirectory).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/uploads/teacher-photos/**")
                .addResourceLocations("file:" + teacherPhotosPath + "/");

        String adminPhotosPath = Paths.get(adminPhotoDirectory).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/uploads/admin-photos/**")
                .addResourceLocations("file:" + adminPhotosPath + "/");

        String schoolLogosPath = Paths.get(schoolLogoDirectory).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/uploads/school-logos/**")
                .addResourceLocations("file:" + schoolLogosPath + "/");
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Spring's default async request timeout (30s) is too short for a streamed AI
        // reply that may involve several tool-calling round trips before the model
        // starts producing the final answer — see AiProxyController's StreamingResponseBody
        // endpoint. Without this, a slow-but-healthy stream gets killed mid-response.
        configurer.setDefaultTimeout(120_000);
    }
}
