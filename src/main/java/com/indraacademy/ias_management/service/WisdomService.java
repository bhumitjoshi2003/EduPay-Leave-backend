package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.WisdomDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class WisdomService {
 private final WisdomThoughtRepository thoughts;
 private final WisdomOverrideRepository overrides;
 private final WisdomVerseRepository verses;
 private final WisdomTeachingRepository teachings;
 private final SchoolRepository schools;
 private final SecurityUtil security;
 private final Clock clock;
 private final AuditService audit;
 private final ObjectMapper json;
 private final EntitlementService entitlements;
 public WisdomService(WisdomThoughtRepository thoughts, WisdomOverrideRepository overrides,
   WisdomVerseRepository verses, WisdomTeachingRepository teachings, SchoolRepository schools,
   SecurityUtil security, Clock clock, AuditService audit, ObjectMapper json, EntitlementService entitlements) {
  this.thoughts=thoughts; this.overrides=overrides; this.verses=verses; this.teachings=teachings;
  this.schools=schools; this.security=security; this.clock=clock; this.audit=audit; this.json=json;
  this.entitlements=entitlements;
 }
 private Long school() {
  if (!Set.of("ADMIN","SUB_ADMIN","TEACHER","STUDENT","PARENT").contains(security.getRole()) || security.getSchoolId()==null)
   throw new AccessDeniedException("School membership required");
  Long id=security.getSchoolId();
  entitlements.requireFeature(id,"WISDOM");
  return id;
 }
 private Long admin() {
  Long id=school();
  if (!Set.of("ADMIN","SUB_ADMIN").contains(security.getRole())) throw new AccessDeniedException("Administrator required");
  return id;
 }
 /** Verse authorship is global, shared-corpus stewardship, never a single school's data — a
  *  school ADMIN must not be able to rewrite scripture every other school also reads. */
 private void superAdmin() {
  if (!"SUPER_ADMIN".equals(security.getRole())) throw new AccessDeniedException("Super admin required");
 }
 private ZoneId zone(Long school) { return SchoolTimeUtil.zoneId(schools.findById(school).orElseThrow()); }
 private LocalDate today(Long school) { return LocalDate.now(clock.withZone(zone(school))); }
 private static String audience(String value) {
  if (!Set.of("EVERYONE","STUDENT","TEACHER","PARENT","ADMIN").contains(value)) throw new IllegalArgumentException("Invalid audience");
  return value;
 }
 private static PageRequest page(int page) { return PageRequest.of(Math.max(0,page),30); }
 private static void version(long actual,long expected) {
  if(actual!=expected) throw new org.springframework.dao.OptimisticLockingFailureException("Content changed; reload before saving");
 }
 private String snapshot(Object value) {
  try { return value==null?null:json.writeValueAsString(value); }
  catch(JsonProcessingException e) { throw new IllegalStateException("Cannot audit Wisdom content",e); }
 }
 private void log(String action,Object value,String old,Long id,HttpServletRequest request) {
  audit.log(security.getUsername(),security.getRole(),action,"Wisdom",String.valueOf(id),old,snapshot(value),request.getRemoteAddr());
 }
 public Dashboard dashboard() {
  Long id=school(); ZoneId z=zone(id); LocalDate date=LocalDate.now(clock.withZone(z));
  String target=security.getRole().equals("SUB_ADMIN")?"ADMIN":security.getRole();
  return new Dashboard(resolveThought(id,date,target),teachings.published(id,clock.instant(),"",page(0)).stream().findFirst().map(this::view).orElse(null),date,z.getId());
 }
 // Calendar rotation is stable across restarts and replicas. No daily job or read-time writes.
 ThoughtView resolveThought(Long school,LocalDate date,String target) {
  Optional<WisdomOverride> override=overrides.findBySchoolIdAndDisplayDateAndAudience(school,date,target);
  if(override.isEmpty()) override=overrides.findBySchoolIdAndDisplayDateAndAudience(school,date,"EVERYONE");
  if(override.isPresent()) {
   WisdomThought t=thoughts.accessible(override.get().getThoughtId(),school).orElseThrow();
   return new ThoughtView(date,t.getBody(),override.get().getAudience(),true);
  }
  List<WisdomThought> pool=thoughts.automatic(target);
  if(pool.isEmpty()) return new ThoughtView(date,"A small act of kindness can brighten someone’s day.","EVERYONE",false);
  int index=(int)Math.floorMod(date.toEpochDay()+school+target.hashCode(),(long)pool.size());
  WisdomThought t=pool.get(index);
  return new ThoughtView(date,t.getBody(),t.getAudience(),false);
 }
 public Page<WisdomThought> thoughts(int p) { return thoughts.library(admin(),page(p)); }
 public Page<WisdomOverride> overrides(int p) { Long id=admin(); return overrides.findBySchoolIdAndDisplayDateGreaterThanEqualOrderByDisplayDateAsc(id,today(id),page(p)); }
 public Management management() { Long id=admin();return new Management(today(id),zone(id).getId(),teachings.existsBySchoolIdAndPublishAtAfter(id,clock.instant())); }
 @Transactional public WisdomThought saveThought(Long id,ThoughtInput input,HttpServletRequest request) {
  Long tenant=admin(); audience(input.audience());
  WisdomThought t=id==null?new WisdomThought():thoughts.accessible(id,tenant).orElseThrow();
  if(id!=null && !tenant.equals(t.getSchoolId())) throw new AccessDeniedException("Copy global thoughts to customize them");
  if(id!=null) version(t.getVersion(),input.version()); String old=id==null?null:snapshot(t);
  t.setSchoolId(tenant);t.setBody(input.body().trim());t.setAudience(input.audience());t.setActive(input.active());
  thoughts.saveAndFlush(t);log("SAVE_THOUGHT",t,old,t.getId(),request);return t;
 }
 @Transactional public WisdomOverride scheduleThought(OverrideInput input,HttpServletRequest request) {
  Long tenant=admin();audience(input.audience());
  if(input.displayDate().isBefore(today(tenant))) throw new IllegalArgumentException("Choose today or a future date");
  WisdomThought thought=thoughts.accessible(input.thoughtId(),tenant).orElseThrow();
  if(!thought.getActive()) throw new IllegalArgumentException("Choose an active thought");
  if(!thought.getAudience().equals("EVERYONE") && !thought.getAudience().equals(input.audience())) throw new IllegalArgumentException("Thought audience does not match schedule");
  WisdomOverride o=overrides.findBySchoolIdAndDisplayDateAndAudience(tenant,input.displayDate(),input.audience()).orElseGet(WisdomOverride::new);
  String old=o.getId()==null?null:snapshot(o);
  o.setSchoolId(tenant);o.setDisplayDate(input.displayDate());o.setAudience(input.audience());o.setThoughtId(thought.getId());
  overrides.saveAndFlush(o);log("SCHEDULE_THOUGHT",o,old,o.getId(),request);return o;
 }
 @Transactional public void removeOverride(Long id,HttpServletRequest request) {
  WisdomOverride o=overrides.findByIdAndSchoolId(id,admin()).orElseThrow();String old=snapshot(o);
  overrides.delete(o);log("REMOVE_THOUGHT_OVERRIDE",null,old,id,request);
 }
 // Global, reviewed source data is read-only to every school and to AI.
 public Page<WisdomVerse> verses(String q,int p) { admin();return verses.search(q.substring(0,Math.min(q.length(),200)),page(p)); }
 public WisdomVerse verifiedVerse(Long id) { admin();return verses.findById(id).orElseThrow(()->new IllegalArgumentException("Select a verified scripture record; no source data is available for this verse")); }
 /** Only a super admin who has personally verified a source may add or edit a single verse —
  *  the corpus is global and shared by every school, so no single school's admin may touch it.
  *  Bulk, reviewed imports go through WisdomVerseImportService instead; either way scripture is
  *  never machine-authored. */
 @Transactional public WisdomVerse saveVerse(Long id,VerseInput input,HttpServletRequest request) {
  superAdmin();
  WisdomVerse v=id==null?new WisdomVerse():verses.findById(id).orElseThrow();
  if(id!=null) version(v.getVersion(),input.version()); String old=id==null?null:snapshot(v);
  v.setChapter(input.chapter());v.setVerse(input.verse());
  v.setSanskrit(input.sanskrit().trim());v.setTransliteration(input.transliteration().trim());v.setTranslation(input.translation().trim());
  v.setSourceName(input.sourceName().trim());v.setSourceUrl(input.sourceUrl().trim());v.setLicense(input.license().trim());
  v.setSourceVersion(input.sourceVersion().trim());v.setVerifiedBy(input.verifiedBy().trim());v.setThemes(input.themes().trim());
  if(id==null) v.setVerifiedAt(clock.instant());
  verses.saveAndFlush(v);log("SAVE_VERSE",v,old,v.getId(),request);return v;
 }
 /** Bounded candidate set for AI-assisted ranking — every verse currently in our verified table,
  *  never anything the model names itself. The AI response is filtered back down to this exact
  *  id set server-side before it ever reaches the client (see WisdomAiController.suggestVerses). */
 /**
  * Every verified verse is eligible for AI ranking — not just ones whose {@code themes} field
  * happens to keyword-match the admin's topic. A 1000-row cap is a safety valve, not a
  * functional limit: the complete Gita corpus is ~700 verses, so this comfortably covers it
  * without requiring every verse to be manually theme-tagged first. Each candidate carries a
  * short translation excerpt (not the full text) so the model can judge relevance semantically
  * from real verse content while keeping the per-call payload bounded and cheap.
  */
 public List<VerseCandidate> candidatesForSuggestion() {
  admin();
  return verses.allOrderedByReference(PageRequest.of(0,1000)).stream()
   .map(v->new VerseCandidate(v.getId(),v.getChapter(),v.getVerse(),v.getThemes(),excerpt(v.getTranslation())))
   .toList();
 }
 private static String excerpt(String translation) {
  if (translation==null) return "";
  return translation.length()<=100 ? translation : translation.substring(0,100);
 }
 public Page<TeachingView> library(String q,int p) {return teachings.published(school(),clock.instant(),q.substring(0,Math.min(q.length(),200)),page(p)).map(this::view);}
 public Page<TeachingView> teachingAdmin(int p) {return teachings.management(admin(),page(p)).map(this::view);}
 public TeachingView read(Long id) {
  WisdomTeaching t=teachings.findByIdAndSchoolId(id,school()).orElseThrow();
  if(t.getPublishAt()==null || t.getPublishAt().isAfter(clock.instant())) throw new NoSuchElementException("Teaching not found");
  return view(t);
 }
 private TeachingView view(WisdomTeaching t) {
  String status=t.getPublishAt()==null?"DRAFT":t.getPublishAt().isAfter(clock.instant())?"SCHEDULED":"PUBLISHED";
  return new TeachingView(t.getId(),t.getVersion(),t.getTitle(),verses.findById(t.getVerseId()).orElseThrow(),t.getSimpleMeaning(),t.getUnderstanding(),t.getLesson(),t.getPublicationDate(),t.getPublicationZone(),status);
 }
 @Transactional public TeachingView saveTeaching(Long id,TeachingInput input,HttpServletRequest request) {
  Long tenant=admin(); verifiedVerse(input.verseId());
  WisdomTeaching t=id==null?new WisdomTeaching():teachings.findByIdAndSchoolId(id,tenant).orElseThrow();
  if(t.getPublishAt()!=null) throw new IllegalArgumentException("Only drafts can be edited; cancel a future schedule first");
  if(id!=null) version(t.getVersion(),input.version());String old=id==null?null:snapshot(t);
  t.setSchoolId(tenant);t.setVerseId(input.verseId());t.setTitle(input.title().trim());t.setSimpleMeaning(input.simpleMeaning().trim());t.setUnderstanding(input.understanding().trim());t.setLesson(input.lesson().trim());
  teachings.saveAndFlush(t);log("SAVE_GITA_DRAFT",t,old,t.getId(),request);return view(t);
 }
 @Transactional public TeachingView scheduleTeaching(Long id,ScheduleInput input,HttpServletRequest request) {
  Long tenant=admin();WisdomTeaching t=teachings.findByIdAndSchoolId(id,tenant).orElseThrow();version(t.getVersion(),input.version());
  if(t.getPublishAt()!=null) throw new IllegalArgumentException("Only drafts can be scheduled");
  if(input.date().isBefore(today(tenant))) throw new IllegalArgumentException("Choose today or a future date");
  verifiedVerse(t.getVerseId());String old=snapshot(t);ZoneId z=zone(tenant);
  LocalTime time=input.time()!=null?input.time():LocalTime.MIDNIGHT;
  t.setPublicationDate(input.date());t.setPublicationZone(z.getId());t.setPublishAt(input.date().atTime(time).atZone(z).toInstant());
  teachings.saveAndFlush(t);log("APPROVE_GITA_PUBLICATION",t,old,id,request);return view(t);
 }
 @Transactional public TeachingView cancelTeaching(Long id,long expected,HttpServletRequest request) {
  WisdomTeaching t=teachings.findByIdAndSchoolId(id,admin()).orElseThrow();version(t.getVersion(),expected);
  if(t.getPublishAt()==null || !t.getPublishAt().isAfter(clock.instant())) throw new IllegalArgumentException("Only future schedules can be cancelled");
  String old=snapshot(t);t.setPublishAt(null);t.setPublicationDate(null);t.setPublicationZone(null);
  teachings.saveAndFlush(t);log("CANCEL_GITA_SCHEDULE",t,old,id,request);return view(t);
 }
}
