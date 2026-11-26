package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * 인증 문자 가짜 발송기(#60, 07 D 3일차). 문자를 보내지 않고, 정해 둔 결과를 돌려주며 마지막 인증번호를 메모리에만 둔다. 로그에는 아무것도 남기지 않는다. 다만
 * 허용된 로컬 개발 환경(local 프로필이 켜져 있고 test·prod 프로필은 꺼져 있을 때)에서 {@code app.sms.log-code=true}면(#248) 화면에서
 * 가입을 이어 가도록 인증번호 원문과 가린 전화번호를 한 줄 남긴다. 전화번호 원문은 남기지 않는다(decisions 2026-11-18·2026-11-26, 04 4-2).
 *
 * <p>{@code app.sms.mode=fake}(local·test)에서만 켜진다. prod에서는 {@code SmsModeGuard}가 기동을 막는다. 솔라피
 * 연동(NOTI-11)이 들어오면 실제 구현으로 바꾸고 이 클래스를 지운다.
 */
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "fake")
public class FakeVerificationSmsSender implements VerificationSmsSender {

  private static final Logger log = LoggerFactory.getLogger(FakeVerificationSmsSender.class);

  /**
   * 인증번호를 로그에 남겨도 되는 프로필 식. {@code application.yml}의 {@code app.sms.log-code} 문서도 이 식을 쓴다. 설정값만 믿지
   * 않고 실제로 켜진 프로필을 다시 보므로, 외부 설정({@code APP_SMS_LOG_CODE=true})으로 켜도 test·prod에서는 남기지 않는다(#250 리뷰).
   */
  static final String CODE_LOG_PROFILES = "local & !test & !prod";

  private final Map<String, String> lastCodes = new ConcurrentHashMap<>();
  private final boolean logCode;
  private volatile VerificationSendResult nextResult = VerificationSendResult.SENT;

  public FakeVerificationSmsSender(
      @Value("${app.sms.log-code:false}") boolean logCode, Environment environment) {
    boolean allowed = environment.acceptsProfiles(Profiles.of(CODE_LOG_PROFILES));
    if (logCode && !allowed) {
      log.warn("app.sms.log-code가 켜져 있지만 로컬 개발 환경이 아니라 인증번호를 로그에 남기지 않아요");
    }
    this.logCode = logCode && allowed;
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
