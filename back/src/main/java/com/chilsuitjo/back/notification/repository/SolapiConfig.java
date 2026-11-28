package com.chilsuitjo.back.notification.repository;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.sms.mode=solapi}일 때만 솔라피 호출 부품을 만든다. 값이 없거나 틀리면 {@code SmsModeGuard}가 그 전에 기동을 막는다. 다른
 * 방식(fake·disabled)에서는 만들지 않아 업체 주소로 아무 요청도 나가지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "solapi")
public class SolapiConfig {

  @Bean
  SolapiSettings solapiSettings(
      @Value("${app.sms.solapi.base-url}") URI baseUrl,
      @Value("${app.sms.solapi.api-key}") String apiKey,
      @Value("${app.sms.solapi.api-secret}") String apiSecret,
      @Value("${app.sms.solapi.sender}") String sender,
      @Value("${app.sms.solapi.connect-timeout:2s}") Duration connectTimeout,
      @Value("${app.sms.solapi.response-timeout:3s}") Duration responseTimeout) {
    return new SolapiSettings(baseUrl, apiKey, apiSecret, sender, connectTimeout, responseTimeout);
  }

  @Bean
  SolapiClient solapiClient(SolapiSettings settings, Clock clock) {
    return new SolapiClient(settings, clock);
  }
}
