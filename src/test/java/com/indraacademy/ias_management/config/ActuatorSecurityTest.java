package com.indraacademy.ias_management.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the Better Stack uptime-monitoring fix: only /api/actuator/health is
 * reachable anonymously (moved under /api since nginx doesn't forward bare
 * /actuator/* paths), no other actuator endpoint is exposed, and every other
 * API keeps requiring authentication exactly as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.url=jdbc:h2:mem:actuator-security-test;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH,SESSION,VALUE,DAY;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
		"spring.flyway.enabled=false",
		"knowledge.pgvector.enabled=false",
		"spring.sql.init.mode=never",
		"frontend.url=http://localhost:4200",
		"spring.mail.username=test",
		"spring.mail.password=test",
		"jwt.private-key=test",
		"jwt.public-key=test",
		"razorpay.key.id=test",
		"razorpay.key.secret=test",
		"app.demo.notify.email=test@example.com",
		"app.tenant.subdomain-validation=false"
})
@AutoConfigureMockMvc
class ActuatorSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthEndpointIsPubliclyAccessibleWithMinimalBody() throws Exception {
		// show-details=never means no per-component (db/redis/mail/etc.) breakdown is
		// ever included — "groups" is just the liveness/readiness probe names
		// (management.endpoint.health.probes.enabled=true), not sensitive detail.
		mockMvc.perform(get("/api/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	void otherActuatorEndpointsAreNotPubliclyAccessible() throws Exception {
		mockMvc.perform(get("/api/actuator/env"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/actuator/beans"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/actuator/metrics"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void protectedApiStillRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/students"))
				.andExpect(status().isUnauthorized());
	}
}
