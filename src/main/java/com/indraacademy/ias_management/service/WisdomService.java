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
 private final SchoolRepository schools;
 private final SecurityUtil security;
 private final Clock clock;
 private final AuditService audit;
 private final ObjectMapper json;
 private final EntitlementService entitlements;
 public WisdomService(WisdomThoughtRepository thoughts, WisdomOverrideRepository overrides, SchoolRepository schools,
   SecurityUtil security, Clock clock, AuditService audit, ObjectMapper json, EntitlementService entitlements) {
  this.thoughts=thoughts; this.overrides=overrides;
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
  return new Dashboard(resolveThought(id,date,target),date,z.getId());
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
 public Management management() { Long id=admin();return new Management(today(id),zone(id).getId()); }
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
}
