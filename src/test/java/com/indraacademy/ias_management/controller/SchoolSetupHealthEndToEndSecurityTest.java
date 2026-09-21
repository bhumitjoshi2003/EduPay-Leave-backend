package com.indraacademy.ias_management.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack proof (real SecurityConfig + real filter chain, no mocked security) that an
 * unauthenticated caller genuinely gets 401 from GET /api/school/setup-health at the HTTP layer
 * — SchoolSetupHealthSecurityTest's reflection check only proves the @PreAuthorize annotation is
 * present, not that it is actually enforced end to end. Mirrors
 * FileUploadRequestSecurityTest/ActuatorSecurityTest's established pattern.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:school-setup-health-security-test;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH,SESSION,VALUE,DAY;DB_CLOSE_DELAY=-1",
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
class SchoolSetupHealthEndToEndSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestIsRejectedWithUnauthorized() throws Exception {
        mockMvc.perform(get("/api/school/setup-health"))
                .andExpect(status().isUnauthorized());
    }
}
