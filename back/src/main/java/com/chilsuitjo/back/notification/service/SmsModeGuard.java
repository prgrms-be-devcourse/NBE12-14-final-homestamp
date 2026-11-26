package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.SmsMode;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 문자 방식({@link SmsMode}, {@code app.sms.mode})을 빈을 만들기 전에 검사한다(decisions 2026-11-25 운영 문자 모드). 다음이면
 * 기동하지 않는다.
 *
 * <ul>
 *   <li>값이 없거나 모르는 값: 다른 방식(가짜·disabled)으로 조용히 바꾸지 않는다
 *   <li>{@code solapi}: 실제 연동이 아직 없다
 *   <li>prod에서 {@code fake}: 보내지 않은 문자를 보낸 것({@code SENT})으로 남기게 된다
 * </ul>
 *
 * <p>빈 후처리기 단계에서 검사해서, 문자 부품이 없어 {@code OutboxWorker}를 못 만드는 알기 어려운 오류보다 먼저 이유를 말하고 멈춘다. 오류 문구에 설정
 * 값을 그대로 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class SmsModeGuard {

  @Bean
  static BeanFactoryPostProcessor smsModeCheck(Environment environment) {
    return beanFactory -> check(environment);
  }

  /** 검사를 통과한 방식. 통과하지 못하면 {@link IllegalStateException}. */
  static SmsMode check(Environment environment) {
    String raw = environment.getProperty(SmsMode.PROPERTY);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException(
          "문자 방식(app.sms.mode)이 없어 시작하지 않는다. local·test는 fake, prod는 disabled 또는 solapi");
    }
    SmsMode mode =
        SmsMode.parse(raw)
            .orElseThrow(() ->
                new IllegalStateException("문자 방식(app.sms.mode)은 fake·disabled·solapi 중 하나다(소문자)"));
    if (mode == SmsMode.SOLAPI) {
      throw new IllegalStateException("솔라피 연동(app.sms.mode=solapi)은 아직 구현이 없어 시작하지 않는다");
    }
    if (mode == SmsMode.FAKE && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "prod에서는 가짜 문자(app.sms.mode=fake)로 시작하지 않는다. 연동 전에는 disabled");
    }
    return mode;
  }
}
