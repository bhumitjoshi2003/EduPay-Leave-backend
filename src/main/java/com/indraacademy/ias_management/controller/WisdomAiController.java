package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.WisdomDtos.*;
import com.indraacademy.ias_management.entity.WisdomVerse;
import com.indraacademy.ias_management.service.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Stateless editorial assistance. No persistence or publishing dependency is exposed to AI. */
@RestController
@RequestMapping("/api/ai/wisdom")
@PreAuthorize("hasAnyRole('"+Role.ADMIN+"','"+Role.SUB_ADMIN+"')")
public class WisdomAiController {
 private final WisdomService wisdom;
 private final EntitlementService entitlements;
 private final SecurityUtil security;
 private final RestTemplate client;
 @Value("${ai.service.url:http://localhost:8001}") private String url;
 @Value("${ai.internal.secret}") private String secret;
 public WisdomAiController(WisdomService wisdom,EntitlementService entitlements,SecurityUtil security) {
  this.wisdom=wisdom;this.entitlements=entitlements;this.security=security;
  SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(5000);factory.setReadTimeout(45000);client=new RestTemplate(factory);
 }
 @PostMapping("/draft") public EditorialDraft draft(@Valid @RequestBody AiInput input) {
  entitlements.requireFeature(security.getSchoolId(),"WISDOM");
  entitlements.requireFeature(security.getSchoolId(),"AI_COPILOT");
  WisdomVerse verse=wisdom.verifiedVerse(input.verseId());
  HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Internal-Secret",secret);
  EditorialDraft result=client.postForObject(url+"/wisdom/draft",new HttpEntity<>(Map.of(
   "theme",input.theme(),"translation",verse.getTranslation()),headers),EditorialDraft.class);
  if(result==null || !valid(result.simpleMeaning(),4000) || !valid(result.understanding(),12000) || !valid(result.lesson(),2000))
   throw new IllegalArgumentException("AI returned an invalid editorial draft; please try again");
  return result;
 }
 /** AI ranks only verses already in our verified table — the candidate set is resolved
  *  server-side (never client-supplied), and every returned verseId is re-checked against that
  *  exact set before this method returns, so the AI can never surface a chapter/verse we have
  *  not ourselves verified. */
 @PostMapping("/suggest-verses") public List<VerseSuggestionView> suggestVerses(@Valid @RequestBody SuggestInput input) {
  entitlements.requireFeature(security.getSchoolId(),"WISDOM");
  entitlements.requireFeature(security.getSchoolId(),"AI_COPILOT");
  List<VerseCandidate> candidates=wisdom.candidatesForSuggestion();
  if(candidates.isEmpty()) return List.of();
  Set<Long> allowed=candidates.stream().map(VerseCandidate::id).collect(Collectors.toSet());
  HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Internal-Secret",secret);
  List<VerseSuggestion> result=client.exchange(url+"/wisdom/suggest-verses",HttpMethod.POST,
   new HttpEntity<>(new SuggestRequest(input.theme(),candidates),headers),
   new ParameterizedTypeReference<List<VerseSuggestion>>(){}).getBody();
  if(result==null) return List.of();
  // Resolve each surviving (id-validated) suggestion to its full verified record — the model
  // only ever saw id/chapter/verse/themes, never Sanskrit/translation, so this lookup is the
  // only source of truth for what the admin actually sees and can select.
  return result.stream().filter(s->s.verseId()!=null && allowed.contains(s.verseId()))
   .map(s->new VerseSuggestionView(wisdom.verifiedVerse(s.verseId()),s.rationale())).toList();
 }
 private boolean valid(String value,int max) {return value!=null && !value.isBlank() && value.length()<=max;}
}
