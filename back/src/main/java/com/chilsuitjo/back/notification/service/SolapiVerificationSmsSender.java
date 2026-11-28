package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.repository.SolapiClient;
import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 솔라피 인증 문자 발송기(NOTI-11, {@code app.sms.mode=solapi}). 아웃박스를 쓰지 않고 바로 한 번 보낸다(04 4-2). 인증번호 원문과 번호는
 * 이 호출 안에서만 쓰고 저장·로그에 남기지 않는다. 자동으로 다시 보내지 않는다.
 *
 * <ul>
 *   <li>접수 → {@code SENT}
 *   <li>받았는지 모름(응답 시간 초과, 5xx, 요청 전체 4xx 등) → {@code UNKNOWN}: 실제로 갔을 수 있어 보낸 것으로 센다(06 ACC-1j)
 *   <li>나가지 않음·거절(연결 실패, 호출 한도, 등록 실패) → {@code FAILED}: 업체가 받지 않은 것이 확실해 auth가 인증 행을 지우고 세지 않는다
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "solapi")
public class SolapiVerificationSmsSender implements VerificationSmsSender {

  private final SolapiClient client;

  public SolapiVerificationSmsSender(SolapiClient client) {
    this.client = client;
  }

  @Override
  public VerificationSendResult send(String phone, String code) {
    return switch (client.send(phone, text(code), Map.of())) {
      case SolapiClient.Accepted accepted -> VerificationSendResult.SENT;
      case SolapiClient.Unknown unknown -> VerificationSendResult.UNKNOWN;
      case SolapiClient.NotSent notSent -> VerificationSendResult.FAILED;
      case SolapiClient.Refused refused -> VerificationSendResult.FAILED;
    };
  }

  /** 인증 문자 본문(임시, 문구는 08에서 정하면 바꾼다). 번호·이름을 넣지 않는다. */
  static String text(String code) {
    return "[홈스탬프] 인증번호는 " + code + "이에요. 5분 안에 입력해 주세요.";
  }
}
