package com.indraacademy.ias_management.controller;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseInput;
import com.indraacademy.ias_management.entity.WisdomVerse;
import com.indraacademy.ias_management.service.WisdomService;
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
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringJUnitConfig(WisdomAuthorizationTest.Config.class)
class WisdomAuthorizationTest {
 @Configuration @EnableMethodSecurity static class Config {
  @Bean WisdomService service(){return mock(WisdomService.class);}
  @Bean WisdomAdminController admin(WisdomService s){return new WisdomAdminController(s);}
  @Bean WisdomController reader(WisdomService s){return new WisdomController(s);}
 }
 @Autowired WisdomAdminController admin; @Autowired WisdomController reader; @Autowired WisdomService service;
 void as(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test","",List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 @ParameterizedTest @ValueSource(strings={"STUDENT","TEACHER","PARENT","SUPER_ADMIN"}) void readersCannotAccessAdmin(String role){as(role);assertThatThrownBy(()->admin.status()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
 @ParameterizedTest @ValueSource(strings={"ADMIN","SUB_ADMIN"}) void adminsCanManage(String role){as(role);assertThatCode(()->admin.status()).doesNotThrowAnyException();}
 @ParameterizedTest @ValueSource(strings={"ADMIN","SUB_ADMIN","STUDENT","TEACHER","PARENT"}) void schoolRolesCanRead(String role){as(role);assertThatCode(()->reader.dashboard()).doesNotThrowAnyException();}
 @org.junit.jupiter.api.Test void anonymousCannotRead(){assertThatThrownBy(()->reader.dashboard()).isInstanceOf(org.springframework.security.core.AuthenticationException.class);}
 private VerseInput verseInput(){return new VerseInput(2,47,"S","T","Tr","Src","https://x","Lic","v1","Rev","duty",0);}
 @ParameterizedTest @ValueSource(strings={"ADMIN","SUB_ADMIN","STUDENT","TEACHER","PARENT","SUPER_ADMIN"})
 void onlySuperAdminCanWriteTheGlobalVerseCorpus(String role){
  as(role);
  if ("SUPER_ADMIN".equals(role)) {
   when(service.saveVerse(any(),any(),any())).thenReturn(new WisdomVerse());
   assertThatCode(()->admin.createVerse(verseInput(),null)).doesNotThrowAnyException();
  } else {
   assertThatThrownBy(()->admin.createVerse(verseInput(),null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
 }
}
