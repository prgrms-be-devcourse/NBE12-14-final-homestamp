package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.SmsMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
 *   <li>{@code solapi}인데 연결 설정({@code app.sms.solapi.*})이 없거나 틀림: 키·비밀·발신번호가 비었거나, 주소가 http(s) 주소
 *       하나가 아니거나, prod에서 공식 주소({@code https://api.solapi.com}, 고정 IP용 {@code
 *       https://api-static.solapi.com})가 아님
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
      checkSolapi(environment);
    }
    if (mode == SmsMode.FAKE && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "prod에서는 가짜 문자(app.sms.mode=fake)로 시작하지 않는다. 연동 전에는 disabled");
    }
    return mode;
  }

  static final String SOLAPI_BASE_URL = "app.sms.solapi.base-url";
  static final List<String> SOLAPI_SECRETS =
      List.of("app.sms.solapi.api-key", "app.sms.solapi.api-secret", "app.sms.solapi.sender");

  /** prod에서 받는 업체 주소. 키로 서명한 요청이 다른 곳으로 가지 않게 정해 둔다(공식 문서 start: 고정 IP용 주소 포함). */
  static final Set<String> PROD_SOLAPI_HOSTS = Set.of("api.solapi.com", "api-static.solapi.com");

  private static final Pattern SENDER = Pattern.compile("[0-9]{8,11}");

  /** 값은 문구에 넣지 않고 설정 이름만 넣는다. */
  private static void checkSolapi(Environment environment) {
    for (String key : SOLAPI_SECRETS) {
      String value = environment.getProperty(key);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException("솔라피 설정이 없어 시작하지 않는다: " + key);
      }
    }
    if (!SENDER.matcher(environment.getProperty("app.sms.solapi.sender")).matches()) {
      throw new IllegalStateException("솔라피 발신번호는 숫자 8~11자리다: app.sms.solapi.sender");
    }
    String raw = environment.getProperty(SOLAPI_BASE_URL);
    URI uri;
    try {
      uri = raw == null ? null : new URI(raw);
    } catch (URISyntaxException e) {
      uri = null;
    }
    boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
    if (uri == null
        || uri.getHost() == null
        || !("https".equals(uri.getScheme()) || (!prod && "http".equals(uri.getScheme())))
        || uri.getRawUserInfo() != null
        || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalStateException(
          "솔라피 주소는 경로 없는 https 주소 하나다(local·test만 http): " + SOLAPI_BASE_URL);
    }
    if (prod && (uri.getPort() != -1 || !PROD_SOLAPI_HOSTS.contains(uri.getHost()))) {
      throw new IllegalStateException("prod 솔라피 주소는 공식 주소만 받는다: " + SOLAPI_BASE_URL);
    }
  }
}
