package com.indraacademy.ias_management.controller;
import com.indraacademy.ias_management.entity.PaymentPricingConfig;
import com.indraacademy.ias_management.repository.PaymentPricingConfigRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PaymentPricingService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Mirrors WisdomAuthorizationTest's convention: real @EnableMethodSecurity proxy over a
 * mocked-service bean, asserting the declarative @PreAuthorize on
 * PlatformPaymentPricingController without any new test infrastructure or HTTP layer. */
@SpringJUnitConfig(PlatformPaymentPricingAuthorizationTest.Config.class)
class PlatformPaymentPricingAuthorizationTest {
 @Configuration @EnableMethodSecurity static class Config {
  // PaymentPricingService is mocked as a concrete class, so its own @Autowired fields still
  // get bean-post-processed during context refresh — these two beans exist only to satisfy
  // that, they are never exercised (list()/cancelScheduled() are stubbed directly below).
  @Bean PaymentPricingConfigRepository repository(){return mock(PaymentPricingConfigRepository.class);}
  @Bean Clock clock(){return Clock.systemUTC();}
  @Bean SecurityUtil securityUtil(){return mock(SecurityUtil.class);}
  @Bean PaymentPricingService service(){
   PaymentPricingService s = mock(PaymentPricingService.class);
   PaymentPricingConfig cfg = new PaymentPricingConfig();
   cfg.setId(1L); cfg.setGatewayProvider("RAZORPAY"); cfg.setGatewayRateBps(200);
   cfg.setGatewayTaxRateBps(1800); cfg.setEdunexifyTransactionFeePaise(2000L);
   cfg.setEffectiveFrom(Instant.now()); cfg.setCreatedAt(Instant.now());
   when(s.list(any())).thenReturn(List.of());
   when(s.cancelScheduled(eq(1L), any())).thenReturn(cfg);
   return s;
  }
  @Bean AuthService authService(){AuthService a = mock(AuthService.class); when(a.getUserId()).thenReturn("it-test"); return a;}
  @Bean PlatformPaymentPricingController controller(){return new PlatformPaymentPricingController();}
 }
 @Autowired PlatformPaymentPricingController controller;
 void as(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test","",List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 @org.junit.jupiter.api.Test void superAdminCanListAndManage(){
  as("SUPER_ADMIN");
  assertThatCode(()->controller.list("RAZORPAY")).doesNotThrowAnyException();
  assertThatCode(()->controller.cancel(1L)).doesNotThrowAnyException();
 }
 @ParameterizedTest @ValueSource(strings={"ADMIN","SUB_ADMIN","TEACHER","STUDENT","PARENT"}) void schoolScopedRolesCannotAccess(String role){
  as(role);
  assertThatThrownBy(()->controller.list("RAZORPAY")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  assertThatThrownBy(()->controller.cancel(1L)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
 }
 @org.junit.jupiter.api.Test void anonymousCannotAccess(){
  assertThatThrownBy(()->controller.list("RAZORPAY")).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
 }
}
