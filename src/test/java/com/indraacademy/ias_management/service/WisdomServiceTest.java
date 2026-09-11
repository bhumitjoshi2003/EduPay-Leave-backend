package com.indraacademy.ias_management.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.WisdomDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.exception.FeatureAccessException;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WisdomServiceTest {
 WisdomThoughtRepository thoughts=mock(WisdomThoughtRepository.class);
 WisdomOverrideRepository overrides=mock(WisdomOverrideRepository.class);
 WisdomVerseRepository verses=mock(WisdomVerseRepository.class);
 WisdomTeachingRepository teachings=mock(WisdomTeachingRepository.class);
 SchoolRepository schools=mock(SchoolRepository.class);
 SecurityUtil security=mock(SecurityUtil.class); AuditService audit=mock(AuditService.class);
 EntitlementService entitlements=mock(EntitlementService.class);
 HttpServletRequest request=mock(HttpServletRequest.class);
 Clock clock=Clock.fixed(Instant.parse("2026-09-13T18:30:00Z"),ZoneOffset.UTC);
 WisdomService service;
 @BeforeEach void setup(){
  service=new WisdomService(thoughts,overrides,verses,teachings,schools,security,clock,audit,new ObjectMapper().findAndRegisterModules(),entitlements);
  when(security.getRole()).thenReturn("ADMIN");when(security.getSchoolId()).thenReturn(1L);
  School school=new School();school.setTimezone("Asia/Kolkata");when(schools.findById(1L)).thenReturn(Optional.of(school));
  when(teachings.published(anyLong(),any(),anyString(),any())).thenReturn(Page.empty());
 }
 WisdomThought thought(long id,String body){WisdomThought t=new WisdomThought();t.setId(id);t.setBody(body);t.setAudience("EVERYONE");t.setActive(true);return t;}
 WisdomTeaching teaching(){WisdomTeaching t=new WisdomTeaching();t.setId(10L);t.setSchoolId(1L);t.setVerseId(5L);t.setTitle("Editorial fixture");t.setSimpleMeaning("Meaning fixture");t.setUnderstanding("Understanding fixture");t.setLesson("Lesson fixture");when(teachings.findByIdAndSchoolId(10L,1L)).thenReturn(Optional.of(t));WisdomVerse v=new WisdomVerse();v.setId(5L);when(verses.findById(5L)).thenReturn(Optional.of(v));return t;}
 @Test void exactOverrideBeatsEveryoneAndAutomatic(){LocalDate d=LocalDate.of(2026,9,14);WisdomOverride o=new WisdomOverride();o.setThoughtId(8L);o.setAudience("STUDENT");when(overrides.findBySchoolIdAndDisplayDateAndAudience(1L,d,"STUDENT")).thenReturn(Optional.of(o));when(thoughts.accessible(8L,1L)).thenReturn(Optional.of(thought(8,"Override")));assertThat(service.resolveThought(1L,d,"STUDENT").body()).isEqualTo("Override");verify(thoughts,never()).automatic(any());}
 @Test void everyoneOverrideBeatsAutomatic(){LocalDate d=LocalDate.of(2026,9,14);WisdomOverride o=new WisdomOverride();o.setThoughtId(8L);o.setAudience("EVERYONE");when(overrides.findBySchoolIdAndDisplayDateAndAudience(1L,d,"EVERYONE")).thenReturn(Optional.of(o));when(thoughts.accessible(8L,1L)).thenReturn(Optional.of(thought(8,"Everyone")));assertThat(service.resolveThought(1L,d,"PARENT").overridden()).isTrue();}
 @Test void fallbackWhenLibraryEmpty(){assertThat(service.dashboard().thought().body()).isNotBlank();assertThat(service.dashboard().thought().overridden()).isFalse();}
 @Test void rotationHasNoRepeatsUntilPoolExhaustedAndIsStable(){when(thoughts.automatic("STUDENT")).thenReturn(List.of(thought(1,"One"),thought(2,"Two"),thought(3,"Three")));LocalDate d=LocalDate.of(2026,1,1);List<String> seen=new ArrayList<>();for(int i=0;i<3;i++)seen.add(service.resolveThought(1L,d.plusDays(i),"STUDENT").body());assertThat(seen).doesNotHaveDuplicates();assertThat(service.resolveThought(1L,d.plusDays(3),"STUDENT").body()).isEqualTo(seen.getFirst());}
 @Test void schoolDateCrossesBoundaryBeforeUtc(){assertThat(service.dashboard().today()).isEqualTo(LocalDate.of(2026,9,14));}
 @Test void schoolInLosAngelesStillUsesPreviousDay(){School s=new School();s.setTimezone("America/Los_Angeles");when(schools.findById(1L)).thenReturn(Optional.of(s));assertThat(service.dashboard().today()).isEqualTo(LocalDate.of(2026,9,13));}
 @Test void futureOverrideNotLookedUpToday(){service.dashboard();verify(overrides).findBySchoolIdAndDisplayDateAndAudience(1L,LocalDate.of(2026,9,14),"ADMIN");verify(overrides,never()).findBySchoolIdAndDisplayDateAndAudience(eq(1L),eq(LocalDate.of(2026,9,15)),any());}
 @Test void subAdminUsesAdminAudience(){when(security.getRole()).thenReturn("SUB_ADMIN");service.dashboard();verify(thoughts).automatic("ADMIN");}
 @Test void schoolIsolationOnRead(){when(security.getSchoolId()).thenReturn(2L);assertThatThrownBy(()->service.read(10L)).isInstanceOf(NoSuchElementException.class);verify(teachings).findByIdAndSchoolId(10L,2L);}
 @Test void schoolCannotModifyGlobalThought(){when(thoughts.accessible(1L,1L)).thenReturn(Optional.of(thought(1,"Global")));assertThatThrownBy(()->service.saveThought(1L,new ThoughtInput("Change","EVERYONE",true,0),request)).isInstanceOf(AccessDeniedException.class);}
 @Test void mismatchedAudienceScheduleRejected(){WisdomThought t=thought(1,"For teachers");t.setAudience("TEACHER");when(thoughts.accessible(1L,1L)).thenReturn(Optional.of(t));assertThatThrownBy(()->service.scheduleThought(new OverrideInput(1L,LocalDate.of(2026,9,15),"EVERYONE"),request)).isInstanceOf(IllegalArgumentException.class);}
 @Test void studentCannotManageThoughts(){when(security.getRole()).thenReturn("STUDENT");assertThatThrownBy(()->service.thoughts(0)).isInstanceOf(AccessDeniedException.class);}
 @Test void superAdminCannotReadSchoolContent(){when(security.getRole()).thenReturn("SUPER_ADMIN");assertThatThrownBy(()->service.dashboard()).isInstanceOf(AccessDeniedException.class);}
 @Test void noSchoolIsDenied(){when(security.getSchoolId()).thenReturn(null);assertThatThrownBy(()->service.dashboard()).isInstanceOf(AccessDeniedException.class);}
 @Test void draftAndFutureTeachingHidden(){WisdomTeaching t=teaching();assertThatThrownBy(()->service.read(10L)).isInstanceOf(NoSuchElementException.class);t.setPublishAt(clock.instant().plusSeconds(1));assertThatThrownBy(()->service.read(10L)).isInstanceOf(NoSuchElementException.class);}
 @Test void publicationVisibleAtExactInstant(){WisdomTeaching t=teaching();t.setPublishAt(clock.instant());assertThat(service.read(10L).status()).isEqualTo("PUBLISHED");}
 @Test void scheduledLifecycleAndSchoolMidnight(){WisdomTeaching t=teaching();TeachingView result=service.scheduleTeaching(10L,new ScheduleInput(LocalDate.of(2026,9,21),null,0),request);assertThat(result.status()).isEqualTo("SCHEDULED");assertThat(t.getPublishAt()).isEqualTo(Instant.parse("2026-09-20T18:30:00Z"));assertThat(service.cancelTeaching(10L,0,request).status()).isEqualTo("DRAFT");}
 @Test void todaySchedulePublishesOnlyAfterExplicitApproval(){teaching();assertThat(service.scheduleTeaching(10L,new ScheduleInput(LocalDate.of(2026,9,14),null,0),request).status()).isEqualTo("PUBLISHED");}
 @Test void explicitTimeIsHonoredInSchoolTimezone(){WisdomTeaching t=teaching();service.scheduleTeaching(10L,new ScheduleInput(LocalDate.of(2026,9,21),LocalTime.of(7,0),0),request);assertThat(t.getPublishAt()).isEqualTo(Instant.parse("2026-09-21T01:30:00Z"));}
 @Test void omittedTimeStillDefaultsToMidnight_backwardCompatible(){WisdomTeaching t=teaching();service.scheduleTeaching(10L,new ScheduleInput(LocalDate.of(2026,9,21),null,0),request);assertThat(t.getPublishAt()).isEqualTo(Instant.parse("2026-09-20T18:30:00Z"));}
 @Test void noUpcomingDoesNotInventTeaching(){assertThat(service.management().upcomingScheduled()).isFalse();assertThat(service.dashboard().teaching()).isNull();verify(teachings,never()).saveAndFlush(any());}
 @Test void saveDraftCannotPublishOrReplaceScripture(){WisdomTeaching t=teaching();TeachingView v=service.saveTeaching(10L,new TeachingInput(5L,"Title","Meaning","Example","Lesson",0),request);assertThat(v.status()).isEqualTo("DRAFT");assertThat(t.getPublishAt()).isNull();verify(verses,never()).save(any());}
 @Test void missingVerifiedVersePreventsSave(){assertThatThrownBy(()->service.saveTeaching(null,new TeachingInput(999L,"Title","Meaning","Example","Lesson",0),request)).isInstanceOf(IllegalArgumentException.class);verify(teachings,never()).saveAndFlush(any());}
 @Test void publishedCannotBeEditedOrCancelled(){WisdomTeaching t=teaching();t.setPublishAt(clock.instant());assertThatThrownBy(()->service.cancelTeaching(10L,0,request)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.saveTeaching(10L,new TeachingInput(5L,"Title","Meaning","Example","Lesson",0),request)).isInstanceOf(IllegalArgumentException.class);}
 @Test void staleVersionRejected(){WisdomTeaching t=teaching();t.setVersion(2);assertThatThrownBy(()->service.scheduleTeaching(10L,new ScheduleInput(LocalDate.of(2026,9,21),null,1),request)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);}
 @Test void savesNewVerifiedVerse(){
  when(security.getRole()).thenReturn("SUPER_ADMIN");
  WisdomVerse saved=service.saveVerse(null,new VerseInput(2,47,"Sanskrit","Transliteration","Translation","Test Source","https://example.test","Test license","v1","Reviewer","duty",0),request);
  assertThat(saved.getChapter()).isEqualTo(2);assertThat(saved.getVerse()).isEqualTo(47);assertThat(saved.getVerifiedAt()).isNotNull();
  verify(verses).saveAndFlush(any());
 }
 @Test void nonSuperAdminCannotSaveVerse(){when(security.getRole()).thenReturn("TEACHER");assertThatThrownBy(()->service.saveVerse(null,new VerseInput(2,47,"S","T","Tr","Src","https://x","Lic","v1","Rev","duty",0),request)).isInstanceOf(AccessDeniedException.class);verify(verses,never()).saveAndFlush(any());}
 @Test void schoolAdminCannotSaveVerse_theCorpusIsGlobalNotPerSchool(){assertThatThrownBy(()->service.saveVerse(null,new VerseInput(2,47,"S","T","Tr","Src","https://x","Lic","v1","Rev","duty",0),request)).isInstanceOf(AccessDeniedException.class);verify(verses,never()).saveAndFlush(any());}
 @Test void editingVerseDoesNotResetVerifiedAt(){when(security.getRole()).thenReturn("SUPER_ADMIN");WisdomVerse v=new WisdomVerse();v.setId(5L);v.setVerifiedAt(Instant.parse("2020-01-01T00:00:00Z"));when(verses.findById(5L)).thenReturn(Optional.of(v));service.saveVerse(5L,new VerseInput(2,47,"S","T","Tr","Src","https://x","Lic","v1","Rev","duty",0),request);assertThat(v.getVerifiedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));}
 @Test void staleVerseVersionRejected(){when(security.getRole()).thenReturn("SUPER_ADMIN");WisdomVerse v=new WisdomVerse();v.setId(5L);v.setVersion(2);when(verses.findById(5L)).thenReturn(Optional.of(v));assertThatThrownBy(()->service.saveVerse(5L,new VerseInput(2,47,"S","T","Tr","Src","https://x","Lic","v1","Rev","duty",1),request)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);}
 @Test void candidatesForSuggestionIsAdminOnlyAndBounded(){
  when(security.getRole()).thenReturn("STUDENT");
  assertThatThrownBy(()->service.candidatesForSuggestion()).isInstanceOf(AccessDeniedException.class);
  when(security.getRole()).thenReturn("ADMIN");
  WisdomVerse fixture=verse(1L,2,47,"duty");fixture.setTranslation("Thou hast a right to action, but never to the fruits thereof.");
  when(verses.allOrderedByReference(any())).thenReturn(List.of(fixture));
  List<VerseCandidate> result=service.candidatesForSuggestion();
  assertThat(result).containsExactly(new VerseCandidate(1L,2,47,"duty","Thou hast a right to action, but never to the fruits thereof."));
 }
 @Test void candidatesForSuggestionTruncatesLongTranslationsToAShortExcerpt(){
  when(security.getRole()).thenReturn("ADMIN");
  WisdomVerse fixture=verse(1L,2,47,"duty");fixture.setTranslation("x".repeat(200));
  when(verses.allOrderedByReference(any())).thenReturn(List.of(fixture));
  assertThat(service.candidatesForSuggestion().get(0).excerpt()).hasSize(100);
 }
 WisdomVerse verse(long id,int chapter,int verseNo,String themes){WisdomVerse v=new WisdomVerse();v.setId(id);v.setChapter(chapter);v.setVerse(verseNo);v.setThemes(themes);return v;}
 @Test void planWithWisdomFeatureIsAllowedAccess(){assertThatCode(()->service.dashboard()).doesNotThrowAnyException();verify(entitlements).requireFeature(1L,"WISDOM");}
 @Test void planWithoutWisdomFeatureIsDeniedAccess(){doThrow(new FeatureAccessException("WISDOM","CAMPUS")).when(entitlements).requireFeature(1L,"WISDOM");assertThatThrownBy(()->service.dashboard()).isInstanceOf(FeatureAccessException.class);}
 @Test void planWithoutWisdomFeatureIsDeniedOnAdminEndpointsToo(){doThrow(new FeatureAccessException("WISDOM","CAMPUS")).when(entitlements).requireFeature(1L,"WISDOM");assertThatThrownBy(()->service.thoughts(0)).isInstanceOf(FeatureAccessException.class);}
 @Test void saveVerseNeverDependsOnAnySchoolsWisdomEntitlement(){when(security.getRole()).thenReturn("SUPER_ADMIN");doThrow(new FeatureAccessException("WISDOM","CAMPUS")).when(entitlements).requireFeature(anyLong(),eq("WISDOM"));assertThatCode(()->service.saveVerse(null,new VerseInput(2,47,"S","T","Tr","Src","https://x","Lic","v1","Rev","duty",0),request)).doesNotThrowAnyException();}
}
