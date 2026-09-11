package com.indraacademy.ias_management.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.WisdomDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.exception.FeatureAccessException;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WisdomServiceTest {
 WisdomThoughtRepository thoughts=mock(WisdomThoughtRepository.class);
 WisdomOverrideRepository overrides=mock(WisdomOverrideRepository.class);
 SchoolRepository schools=mock(SchoolRepository.class);
 SecurityUtil security=mock(SecurityUtil.class); AuditService audit=mock(AuditService.class);
 EntitlementService entitlements=mock(EntitlementService.class);
 HttpServletRequest request=mock(HttpServletRequest.class);
 Clock clock=Clock.fixed(Instant.parse("2026-09-13T18:30:00Z"),ZoneOffset.UTC);
 WisdomService service;
 @BeforeEach void setup(){
  service=new WisdomService(thoughts,overrides,schools,security,clock,audit,new ObjectMapper().findAndRegisterModules(),entitlements);
  when(security.getRole()).thenReturn("ADMIN");when(security.getSchoolId()).thenReturn(1L);
  School school=new School();school.setTimezone("Asia/Kolkata");when(schools.findById(1L)).thenReturn(Optional.of(school));
 }
 WisdomThought thought(long id,String body){WisdomThought t=new WisdomThought();t.setId(id);t.setBody(body);t.setAudience("EVERYONE");t.setActive(true);return t;}
 @Test void exactOverrideBeatsEveryoneAndAutomatic(){LocalDate d=LocalDate.of(2026,9,14);WisdomOverride o=new WisdomOverride();o.setThoughtId(8L);o.setAudience("STUDENT");when(overrides.findBySchoolIdAndDisplayDateAndAudience(1L,d,"STUDENT")).thenReturn(Optional.of(o));when(thoughts.accessible(8L,1L)).thenReturn(Optional.of(thought(8,"Override")));assertThat(service.resolveThought(1L,d,"STUDENT").body()).isEqualTo("Override");verify(thoughts,never()).automatic(any());}
 @Test void everyoneOverrideBeatsAutomatic(){LocalDate d=LocalDate.of(2026,9,14);WisdomOverride o=new WisdomOverride();o.setThoughtId(8L);o.setAudience("EVERYONE");when(overrides.findBySchoolIdAndDisplayDateAndAudience(1L,d,"EVERYONE")).thenReturn(Optional.of(o));when(thoughts.accessible(8L,1L)).thenReturn(Optional.of(thought(8,"Everyone")));assertThat(service.resolveThought(1L,d,"PARENT").overridden()).isTrue();}
 @Test void fallbackWhenLibraryEmpty(){assertThat(service.dashboard().thought().body()).isNotBlank();assertThat(service.dashboard().thought().overridden()).isFalse();}
 @Test void rotationHasNoRepeatsUntilPoolExhaustedAndIsStable(){when(thoughts.automatic("STUDENT")).thenReturn(List.of(thought(1,"One"),thought(2,"Two"),thought(3,"Three")));LocalDate d=LocalDate.of(2026,1,1);List<String> seen=new ArrayList<>();for(int i=0;i<3;i++)seen.add(service.resolveThought(1L,d.plusDays(i),"STUDENT").body());assertThat(seen).doesNotHaveDuplicates();assertThat(service.resolveThought(1L,d.plusDays(3),"STUDENT").body()).isEqualTo(seen.getFirst());}
 @Test void schoolDateCrossesBoundaryBeforeUtc(){assertThat(service.dashboard().today()).isEqualTo(LocalDate.of(2026,9,14));}
 @Test void schoolInLosAngelesStillUsesPreviousDay(){School s=new School();s.setTimezone("America/Los_Angeles");when(schools.findById(1L)).thenReturn(Optional.of(s));assertThat(service.dashboard().today()).isEqualTo(LocalDate.of(2026,9,13));}
 @Test void futureOverrideNotLookedUpToday(){service.dashboard();verify(overrides).findBySchoolIdAndDisplayDateAndAudience(1L,LocalDate.of(2026,9,14),"ADMIN");verify(overrides,never()).findBySchoolIdAndDisplayDateAndAudience(eq(1L),eq(LocalDate.of(2026,9,15)),any());}
 @Test void subAdminUsesAdminAudience(){when(security.getRole()).thenReturn("SUB_ADMIN");service.dashboard();verify(thoughts).automatic("ADMIN");}
 @Test void schoolCannotModifyGlobalThought(){when(thoughts.accessible(1L,1L)).thenReturn(Optional.of(thought(1,"Global")));assertThatThrownBy(()->service.saveThought(1L,new ThoughtInput("Change","EVERYONE",true,0),request)).isInstanceOf(AccessDeniedException.class);}
 @Test void mismatchedAudienceScheduleRejected(){WisdomThought t=thought(1,"For teachers");t.setAudience("TEACHER");when(thoughts.accessible(1L,1L)).thenReturn(Optional.of(t));assertThatThrownBy(()->service.scheduleThought(new OverrideInput(1L,LocalDate.of(2026,9,15),"EVERYONE"),request)).isInstanceOf(IllegalArgumentException.class);}
 @Test void studentCannotManageThoughts(){when(security.getRole()).thenReturn("STUDENT");assertThatThrownBy(()->service.thoughts(0)).isInstanceOf(AccessDeniedException.class);}
 @Test void superAdminCannotReadSchoolContent(){when(security.getRole()).thenReturn("SUPER_ADMIN");assertThatThrownBy(()->service.dashboard()).isInstanceOf(AccessDeniedException.class);}
 @Test void noSchoolIsDenied(){when(security.getSchoolId()).thenReturn(null);assertThatThrownBy(()->service.dashboard()).isInstanceOf(AccessDeniedException.class);}
 @Test void planWithWisdomFeatureIsAllowedAccess(){assertThatCode(()->service.dashboard()).doesNotThrowAnyException();verify(entitlements).requireFeature(1L,"WISDOM");}
 @Test void planWithoutWisdomFeatureIsDeniedAccess(){doThrow(new FeatureAccessException("WISDOM","CAMPUS")).when(entitlements).requireFeature(1L,"WISDOM");assertThatThrownBy(()->service.dashboard()).isInstanceOf(FeatureAccessException.class);}
 @Test void planWithoutWisdomFeatureIsDeniedOnAdminEndpointsToo(){doThrow(new FeatureAccessException("WISDOM","CAMPUS")).when(entitlements).requireFeature(1L,"WISDOM");assertThatThrownBy(()->service.thoughts(0)).isInstanceOf(FeatureAccessException.class);}
}
