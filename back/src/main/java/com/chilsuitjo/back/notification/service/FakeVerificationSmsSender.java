package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 인증 문자 가짜 발송기(#60, 07 D 3일차). 문자를 보내지 않고, 정해 둔 결과를 돌려주며 마지막 인증번호를 메모리에만 둔다. 로그에는 아무것도 남기지 않는다.
 *
 * <p>{@code app.sms.mode=fake}(local·test)에서만 켜진다. prod에서는 {@code SmsModeGuard}가 기동을 막는다. 솔라피
 * 연동(NOTI-11)이 들어오면 실제 구현으로 바꾸고 이 클래스를 지운다.
 */
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "fake")
public class FakeVerificationSmsSender implements VerificationSmsSender {

  private final Map<String, String> lastCodes = new ConcurrentHashMap<>();
  private volatile VerificationSendResult nextResult = VerificationSendResult.SENT;

  @Override
  public VerificationSendResult send(String phone, String code) {
    VerificationSendResult result = nextResult;
    if (result != VerificationSendResult.FAILED) {
      lastCodes.put(phone, code);
    }
    return result;
  }

  /** 다음부터 돌려줄 결과(실패·결과 모름을 시험할 때). */
  public void respondWith(VerificationSendResult result) {
    nextResult = result;
  }

  /** 그 번호로 마지막에 "보낸" 인증번호. 테스트에서 인증을 이어 가려고 쓴다. */
  public Optional<String> lastCodeFor(String phone) {
    return Optional.ofNullable(lastCodes.get(phone));
  }
}
