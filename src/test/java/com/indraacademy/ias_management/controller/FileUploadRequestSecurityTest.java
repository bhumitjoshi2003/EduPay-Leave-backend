package com.indraacademy.ias_management.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end (real SecurityConfig + real filter chain, no mocked security) regression for the
 * "object storage upload auth bug": JwtAuthFilter and TenantValidationFilter both used to exempt
 * the entire /api/files/ prefix from authentication/tenant checks (meant only for the legacy
 * /api/files/uploadEventImage), which silently also exempted these two brand-new endpoints —
 * except @PreAuthorize("isAuthenticated()") then denied every caller anyway, since JWT parsing
 * never ran and left Spring Security's anonymous principal in place. See JwtAuthFilterTest and
 * TenantValidationFilterTest for the fix proven at the filter-unit level; this test proves the
 * same boundary through the real, fully-wired security stack via MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.url=jdbc:h2:mem:file-upload-request-security-test;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH,SESSION,VALUE,DAY;DB_CLOSE_DELAY=-1",
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
class FileUploadRequestSecurityTest {

	@org.springframework.beans.factory.annotation.Autowired
	private MockMvc mockMvc;

	@Test
	void uploadRequestIsNotPubliclyAccessible() throws Exception {
		mockMvc.perform(post("/api/files/upload-request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void completeIsNotPubliclyAccessible() throws Exception {
		mockMvc.perform(post("/api/files/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void legacyUploadEventImageRemainsPubliclyReachable() throws Exception {
		// Must NOT be 401 — this is the one endpoint the /api/files/ exemption was always meant
		// for (SecurityConfig.requestMatchers("/api/files/uploadEventImage").permitAll()), and it
		// must stay reachable past the filter/security layer exactly as before this fix. Its own
		// downstream validation (missing multipart body) is irrelevant to this test.
		mockMvc.perform(post("/api/files/uploadEventImage"))
				.andExpect(result -> {
					int status = result.getResponse().getStatus();
					if (status == 401) {
						throw new AssertionError("Expected the legacy public endpoint to remain reachable past security, but got 401");
					}
				});
	}
}
