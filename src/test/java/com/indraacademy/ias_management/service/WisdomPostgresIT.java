package com.indraacademy.ias_management.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.ClockConfig;
import com.indraacademy.ias_management.dto.WisdomDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({WisdomService.class,WisdomPostgresIT.Config.class})
@EnabledIfEnvironmentVariable(named="WISDOM_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55439/wisdom_verify")
class WisdomPostgresIT {
 @TestConfiguration static class Config {
  @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-09-13T18:30:00Z"),ZoneOffset.UTC);}
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
 }
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String url=System.getenv("WISDOM_TEST_DB_URL");
  // This test intentionally accepts only the disposable local verification database.
  if(!"jdbc:postgresql://127.0.0.1:55439/wisdom_verify".equals(url))throw new IllegalStateException("Disposable database required");
  Flyway before=Flyway.configure().dataSource(url,"wisdom_test","").target("58").load();
  before.migrate();assertThat(before.info().current().getVersion().getVersion()).isEqualTo("58");
  Flyway after=Flyway.configure().dataSource(url,"wisdom_test","").load();after.migrate();after.validate();
  assertThat(after.info().current().getVersion().getVersion()).isEqualTo("61");
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"wisdom_test");r.add("spring.datasource.password",()->"");r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
 }
 @Autowired JdbcTemplate jdbc;@Autowired WisdomService service;@Autowired WisdomThoughtRepository thoughts;
 @MockBean SecurityUtil security;@MockBean AuditService audit;@MockBean EntitlementService entitlements;
 HttpServletRequest request=mock(HttpServletRequest.class);
 @BeforeEach void seed(){
  jdbc.update("INSERT INTO school(id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES(-99001,true,CURRENT_TIMESTAMP,'wisdom fixture','TRIAL','wisdom-fixture',4,8,'Asia/Kolkata'),(-99002,true,CURRENT_TIMESTAMP,'wisdom other','TRIAL','wisdom-other',4,8,'America/Los_Angeles')");
  when(security.getSchoolId()).thenReturn(-99001L);when(security.getRole()).thenReturn("ADMIN");
 }
 @Test void validatesMigrationAndOriginalSeed(){assertThat(jdbc.queryForObject("select count(*) from wisdom_thought where school_id is null",Integer.class)).isEqualTo(155);assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('59','60','61') and success",Integer.class)).isEqualTo(3);}
 @Test void curatedThoughtLibraryHasNoDuplicateBodiesAndCoversEveryAudienceInRange(){
  assertThat(jdbc.queryForObject("select count(*) from (select body from wisdom_thought where school_id is null group by body having count(*)>1) d",Integer.class)).isEqualTo(0);
  int everyone=jdbc.queryForObject("select count(*) from wisdom_thought where school_id is null and audience='EVERYONE'",Integer.class);
  int student=jdbc.queryForObject("select count(*) from wisdom_thought where school_id is null and audience='STUDENT'",Integer.class);
  int teacher=jdbc.queryForObject("select count(*) from wisdom_thought where school_id is null and audience='TEACHER'",Integer.class);
  int parent=jdbc.queryForObject("select count(*) from wisdom_thought where school_id is null and audience='PARENT'",Integer.class);
  assertThat(everyone).isBetween(90,120);
  assertThat(student).isBetween(15,25);
  assertThat(teacher).isBetween(15,25);
  assertThat(parent).isBetween(15,25);
  assertThat(jdbc.queryForObject("select count(*) from wisdom_thought where school_id is null and (trim(body)='' or length(body)>300)",Integer.class)).isEqualTo(0);
 }
 @Test void overrideWinsAndIsTenantScoped(){WisdomThought t=service.saveThought(null,new ThoughtInput("School thought","EVERYONE",true,0),request);service.scheduleThought(new OverrideInput(t.getId(),LocalDate.of(2026,9,14),"EVERYONE"),request);assertThat(service.dashboard().thought().body()).isEqualTo("School thought");when(security.getSchoolId()).thenReturn(-99002L);assertThat(service.dashboard().thought().overridden()).isFalse();assertThatThrownBy(()->service.scheduleThought(new OverrideInput(t.getId(),LocalDate.of(2026,9,14),"EVERYONE"),request)).isInstanceOf(java.util.NoSuchElementException.class);}
 @Test void exactAudienceOverridesEveryone(){WisdomThought all=service.saveThought(null,new ThoughtInput("All","EVERYONE",true,0),request);WisdomThought students=service.saveThought(null,new ThoughtInput("Students","STUDENT",true,0),request);service.scheduleThought(new OverrideInput(all.getId(),LocalDate.of(2026,9,14),"EVERYONE"),request);service.scheduleThought(new OverrideInput(students.getId(),LocalDate.of(2026,9,14),"STUDENT"),request);when(security.getRole()).thenReturn("STUDENT");assertThat(service.dashboard().thought().body()).isEqualTo("Students");when(security.getRole()).thenReturn("PARENT");assertThat(service.dashboard().thought().body()).isEqualTo("All");}
 @Test void futureOverrideAndEmptyQueueDoNotPublishEarly(){WisdomThought t=service.saveThought(null,new ThoughtInput("Tomorrow","EVERYONE",true,0),request);service.scheduleThought(new OverrideInput(t.getId(),LocalDate.of(2026,9,15),"EVERYONE"),request);assertThat(service.dashboard().thought().overridden()).isFalse();}
}
