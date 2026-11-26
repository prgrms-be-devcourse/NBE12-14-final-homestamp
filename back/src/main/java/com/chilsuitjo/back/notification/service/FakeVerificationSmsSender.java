package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 인증 문자 가짜 발송기(#60, 07 D 3일차). 문자를 보내지 않고, 정해 둔 결과를 돌려주며 마지막 인증번호를 메모리에만 둔다. 로그에는 아무것도 남기지 않는다. 다만
 * {@code app.sms.log-code=true}(local 프로필만, #248)면 화면에서 가입을 이어 가도록 인증번호와 가린 번호를 한 줄 남긴다. 원문 번호는 남기지
 * 않는다(decisions 2026-11-18).
 *
 * <p>솔라피 연동(NOTI-11)이 들어오면 실제 구현으로 바꾸고 이 클래스를 지운다.
 */
@Service
public class FakeVerificationSmsSender implements VerificationSmsSender {

  private static final Logger log = LoggerFactory.getLogger(FakeVerificationSmsSender.class);

  private final Map<String, String> lastCodes = new ConcurrentHashMap<>();
  private final boolean logCode;
  private volatile VerificationSendResult nextResult = VerificationSendResult.SENT;

  public FakeVerificationSmsSender(@Value("${app.sms.log-code:false}") boolean logCode) {
    this.logCode = logCode;
  }

  @Override
  public VerificationSendResult send(String phone, String code) {
    VerificationSendResult result = nextResult;
    if (result != VerificationSendResult.FAILED) {
      lastCodes.put(phone, code);
      if (logCode) {
        log.info("[로컬 전용] 인증번호 {} (받는 번호 {})", code, maskPhone(phone));
      }
    }
    return result;
  }

  /** 앞 3자리와 끝 4자리만 남긴다. 예) 01012345678 → 010-****-5678. 너무 짧으면 모두 가린다. */
  static String maskPhone(String phone) {
    if (phone == null || phone.length() < 8) {
      return "****";
    }
    return phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4);
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
