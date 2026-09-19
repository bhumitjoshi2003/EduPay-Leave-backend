package com.indraacademy.ias_management.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.url=jdbc:h2:mem:hikari-configuration-test;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH,SESSION,VALUE,DAY;DB_CLOSE_DELAY=-1",
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
class HikariConfigurationTest {

	@Autowired
	private DataSource dataSource;

	@Test
	void appliesScaleToZeroOrientedPoolConfiguration() {
		assertThat(dataSource).isInstanceOf(HikariDataSource.class);
		HikariDataSource hikari = (HikariDataSource) dataSource;

		assertThat(hikari.getMaximumPoolSize()).isEqualTo(8);
		assertThat(hikari.getMinimumIdle()).isZero();
		assertThat(hikari.getConnectionTimeout()).isEqualTo(5_000);
		assertThat(hikari.getIdleTimeout()).isEqualTo(120_000);
		assertThat(hikari.getMaxLifetime()).isEqualTo(1_500_000);
		assertThat(hikari.getKeepaliveTime()).isZero();
		assertThat(hikari.getValidationTimeout()).isEqualTo(3_000);
		assertThat(hikari.getConnectionTestQuery()).isNull();
	}
}
