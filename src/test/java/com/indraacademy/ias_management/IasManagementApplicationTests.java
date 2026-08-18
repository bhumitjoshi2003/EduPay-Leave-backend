package com.indraacademy.ias_management;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ias-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.flyway.enabled=false",
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
class IasManagementApplicationTests {

	@Test
	void contextLoads() {
	}

}
