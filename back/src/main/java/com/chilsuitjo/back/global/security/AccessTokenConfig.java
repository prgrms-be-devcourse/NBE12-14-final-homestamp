package com.chilsuitjo.back.global.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 실제 액세스 토큰 검증기({@link AccessTokens}). 로그인 토큰 키가 있을 때만 켜진다({@link ConditionalOnLoginTokenKeys}). 키는
 * local·test만 {@code application.yml}에 고정 값이 있고, 운영은 Parameter Store에서 받는다(04 비밀값).
 */
@Configuration
public class AccessTokenConfig {

  @Bean
  @ConditionalOnLoginTokenKeys
  AccessTokens accessTokens(
      @Value("${" + ConditionalOnLoginTokenKeys.ACCESS_KEY + ":}") String key,
      Clock clock,
      @Value("${auth.access.ttl}") Duration ttl) {
    return new AccessTokens(key, clock, ttl);
  }
}
