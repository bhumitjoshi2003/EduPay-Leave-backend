package com.indraacademy.ias_management;

import jakarta.annotation.PostConstruct;
import org.modelmapper.ModelMapper;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing
public class IasManagementApplication {

	@Bean
	public ModelMapper modelMapper() {
		return new ModelMapper();
	}

	public static void main(String[] args) {
		SpringApplication.run(IasManagementApplication.class, args);
	}

	@PostConstruct
	public void init() {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
	}

	// Phase 3: removed a WebMvcConfigurer.addResourceHandlers override mapping
	// /uploads/images/** -> file:uploads/images/ — pre-dates even the current JWT auth system
	// (last touched during the original Keycloak-integration prototype, per git history) and had
	// no writer anywhere in the codebase; genuinely orphaned, not part of any migrated category.
}