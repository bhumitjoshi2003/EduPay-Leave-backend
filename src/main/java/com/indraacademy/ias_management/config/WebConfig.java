package com.indraacademy.ias_management.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FileStorageProperties fileStorageProperties;

    @Value("${student.photo.directory:./uploads/student-photos}")
    private String studentPhotoDirectory;

    @Autowired
    public WebConfig(FileStorageProperties fileStorageProperties) {
        this.fileStorageProperties = fileStorageProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String eventsPath = Paths.get(fileStorageProperties.getDirectory()).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/uploads/events/images/**")
                .addResourceLocations("file:" + eventsPath + "/");

        String photosPath = Paths.get(studentPhotoDirectory).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/uploads/student-photos/**")
                .addResourceLocations("file:" + photosPath + "/");
    }
}