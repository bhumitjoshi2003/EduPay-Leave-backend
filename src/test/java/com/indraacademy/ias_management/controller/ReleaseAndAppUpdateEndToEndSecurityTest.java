package com.indraacademy.ias_management.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack proof (real SecurityConfig + real filter chain) that GET /api/releases/** requires
 * authentication while GET /api/public/app-update does not — mirrors
 * FileUploadRequestSecurityTest / SchoolSetupHealthEndToEndSecurityTest's established pattern.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:release-app-update-security-test;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH,SESSION,VALUE,DAY;DB_CLOSE_DELAY=-1",
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
class ReleaseAndAppUpdateEndToEndSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedReleasesRequestIsRejectedWithUnauthorized() throws Exception {
        mockMvc.perform(get("/api/releases/latest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedAppUpdateRequestSucceeds_itIsIntentionallyPublic() throws Exception {
        mockMvc.perform(get("/api/public/app-update"))
                .andExpect(status().isOk());
    }
}
