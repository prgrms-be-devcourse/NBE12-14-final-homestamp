package com.chilsuitjo.back.notification.repository;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 솔라피 연결 설정({@code app.sms.solapi.*}, NOTI-11). 값 검사는 {@code SmsModeGuard}가 기동 전에 한다.
 *
 * <p>{@code toString}은 키·비밀·발신번호를 보여 주지 않는다.
 *
 * @param baseUrl 업체 주소. prod는 {@code https://api.solapi.com}만 받는다(guard)
 * @param apiKey API Key(SSM SecureString)
 * @param apiSecret API Secret(SSM SecureString). 서명에만 쓰고 보내지 않는다
 * @param sender 등록한 발신번호(숫자만)
 * @param connectTimeout 연결 시간 제한. 이 안에 연결하지 못하면 업체에 닿지 않은 것이 확실하다
 * @param responseTimeout 요청을 보낸 뒤 답을 기다리는 시간. 지나면 업체가 받았는지 모른다
 */
public record SolapiSettings(
    URI baseUrl,
    String apiKey,
    String apiSecret,
    String sender,
    Duration connectTimeout,
    Duration responseTimeout) {

  public SolapiSettings {
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(apiKey, "apiKey");
    Objects.requireNonNull(apiSecret, "apiSecret");
    Objects.requireNonNull(sender, "sender");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(responseTimeout, "responseTimeout");
  }

  @Override
  public String toString() {
    return "SolapiSettings[baseUrl="
        + baseUrl
        + ", apiKey=***, apiSecret=***, sender=***, connectTimeout="
        + connectTimeout
        + ", responseTimeout="
        + responseTimeout
        + "]";
  }
}
