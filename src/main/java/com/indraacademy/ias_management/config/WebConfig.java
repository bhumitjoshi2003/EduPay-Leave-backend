package com.indraacademy.ias_management.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FileStorageProperties fileStorageProperties;

    @Autowired
    public WebConfig(FileStorageProperties fileStorageProperties) {
        this.fileStorageProperties = fileStorageProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Map the /uploads/events/images/** URL pattern to the file system directory
        String absolutePath = Paths.get(fileStorageProperties.getDirectory()).toAbsolutePath().normalize().toString();

        registry.addResourceHandler("/uploads/events/images/**")
                .addResourceLocations("file:" + absolutePath + "/"); // Ensure trailing slash for directory
    }
}