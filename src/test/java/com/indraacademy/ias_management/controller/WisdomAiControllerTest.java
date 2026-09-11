package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.WisdomDtos.AiInput;
import com.indraacademy.ias_management.dto.WisdomDtos.SuggestInput;
import com.indraacademy.ias_management.exception.FeatureAccessException;
import com.indraacademy.ias_management.service.EntitlementService;
import com.indraacademy.ias_management.service.WisdomService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Proves the AI-assisted Wisdom endpoints require WISDOM in addition to (not instead of)
 * AI_COPILOT, and that WISDOM is checked first — before any call reaches WisdomService or the
 * network — so a school without the Wisdom feature can never trigger an outbound AI call for it,
 * even if AI_COPILOT is present.
 */
class WisdomAiControllerTest {
 WisdomService wisdom = mock(WisdomService.class);
 EntitlementService entitlements = mock(EntitlementService.class);
 SecurityUtil security = mock(SecurityUtil.class);
 WisdomAiController controller;

 @BeforeEach void setup() {
  controller = new WisdomAiController(wisdom, entitlements, security);
  when(security.getSchoolId()).thenReturn(1L);
 }

 @Test void draftDeniedWhenWisdomFeatureMissing_evenWithAiCopilot() {
  doThrow(new FeatureAccessException("WISDOM", "CAMPUS")).when(entitlements).requireFeature(1L, "WISDOM");
  assertThatThrownBy(() -> controller.draft(new AiInput(5L, "theme"))).isInstanceOf(FeatureAccessException.class);
  verify(entitlements, never()).requireFeature(1L, "AI_COPILOT");
  verifyNoInteractions(wisdom);
 }

 @Test void draftDeniedWhenAiCopilotMissing_evenWithWisdom() {
  doThrow(new FeatureAccessException("AI_COPILOT", "CAMPUS")).when(entitlements).requireFeature(1L, "AI_COPILOT");
  assertThatThrownBy(() -> controller.draft(new AiInput(5L, "theme"))).isInstanceOf(FeatureAccessException.class);
  verify(entitlements).requireFeature(1L, "WISDOM");
  verifyNoInteractions(wisdom);
 }

 @Test void suggestVersesDeniedWhenWisdomFeatureMissing_evenWithAiCopilot() {
  doThrow(new FeatureAccessException("WISDOM", "CAMPUS")).when(entitlements).requireFeature(1L, "WISDOM");
  assertThatThrownBy(() -> controller.suggestVerses(new SuggestInput("anger"))).isInstanceOf(FeatureAccessException.class);
  verify(entitlements, never()).requireFeature(1L, "AI_COPILOT");
  verifyNoInteractions(wisdom);
 }

 @Test void suggestVersesDeniedWhenAiCopilotMissing_evenWithWisdom() {
  doThrow(new FeatureAccessException("AI_COPILOT", "CAMPUS")).when(entitlements).requireFeature(1L, "AI_COPILOT");
  assertThatThrownBy(() -> controller.suggestVerses(new SuggestInput("anger"))).isInstanceOf(FeatureAccessException.class);
  verify(entitlements).requireFeature(1L, "WISDOM");
  verifyNoInteractions(wisdom);
 }

 @Test void draftProceedsPastEntitlementChecksWhenBothFeaturesPresent() {
  when(wisdom.verifiedVerse(5L)).thenThrow(new IllegalArgumentException("stop before the network call"));
  assertThatThrownBy(() -> controller.draft(new AiInput(5L, "theme"))).isInstanceOf(IllegalArgumentException.class);
  verify(entitlements).requireFeature(1L, "WISDOM");
  verify(entitlements).requireFeature(1L, "AI_COPILOT");
 }

 @Test void suggestVersesProceedsPastEntitlementChecksWhenBothFeaturesPresent() {
  when(wisdom.candidatesForSuggestion()).thenThrow(new IllegalArgumentException("stop before the network call"));
  assertThatThrownBy(() -> controller.suggestVerses(new SuggestInput("anger"))).isInstanceOf(IllegalArgumentException.class);
  verify(entitlements).requireFeature(1L, "WISDOM");
  verify(entitlements).requireFeature(1L, "AI_COPILOT");
 }
}
