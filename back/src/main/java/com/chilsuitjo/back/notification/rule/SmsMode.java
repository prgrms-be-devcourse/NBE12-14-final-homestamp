package com.chilsuitjo.back.notification.rule;

import java.util.Arrays;
import java.util.Optional;

/**
 * 문자를 보내는 방식(설정 {@code app.sms.mode}, decisions 2026-11-25 운영 문자 모드). 값은 소문자 그대로 적는다. 고르는 규칙과 잘못된
 * 조합의 기동 거절은 {@code SmsModeGuard}가 맡는다.
 */
public enum SmsMode {
  /** 보내지 않고 정해 둔 결과를 돌려준다. local·test 전용이고 prod에서는 기동하지 않는다. */
  FAKE("fake"),
  /** 보내지 않고 실패로 남긴다(성공을 기록하지 않음). 실제 연동 전 prod 배포 검증용이며, 이 상태로 실제 사용자에게 서비스를 열지 않는다. */
  DISABLED("disabled"),
  /** 솔라피로 실제로 보낸다(NOTI-11). 연결 설정이 없거나 틀리면 기동하지 않는다({@code SmsModeGuard}). */
  SOLAPI("solapi");

  /** 설정 이름. */
  public static final String PROPERTY = "app.sms.mode";

  private final String value;

  SmsMode(String value) {
    this.value = value;
  }

  /** 설정에 적는 값. */
  public String value() {
    return value;
  }

  /** 설정 값을 읽는다. 대소문자·앞뒤 공백이 다르거나 모르는 값이면 빈 값(조용히 다른 방식으로 바꾸지 않음). */
  public static Optional<SmsMode> parse(String raw) {
    return Arrays.stream(values()).filter(mode -> mode.value.equals(raw)).findFirst();
  }
}
