package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.dto.IssuedCode;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import com.chilsuitjo.back.notification.service.VerificationSmsSender;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 인증 문자 보내기(06 ACC-1e·1i·1j·1k, 04 4-2 "인증 문자는 아웃박스를 쓰지 않는다"). {@link
 * PhoneVerificationService#issue}가 인증 행을 커밋한 <b>뒤</b> notification의 {@link VerificationSmsSender}를
 * 부른다.
 *
 * <ul>
 *   <li>{@code SENT}: 보냈다
 *   <li>{@code FAILED}: 문자 업체가 실패로 답했다. 행을 지워 하루 횟수·다시 받기 간격에 세지 않고({@code sms-total}은 되돌리지 않음)
 *       {@code SMS_SEND_FAILED}로 답한다
 *   <li>{@code UNKNOWN}: 시간 안에 답이 없었다. 실제로 갔을 수 있어 보낸 것으로 보고 인증번호를 그대로 둔다
 * </ul>
 *
 * <p>어느 경우에도 자동으로 다시 보내지 않는다. 원문 인증번호는 보내기 호출에만 넘기고, 번호와 원문은 로그에 남기지 않는다(로그에는 인증 ID와 목적만). 다시 받기
 * 간격·하루 인증 횟수·전체 문자 한도는 {@link PhoneVerificationService#issue}가 그대로 본다. 트랜잭션 밖에서만 부른다(문자 업체 호출 동안
 * DB 연결·잠금을 잡지 않게).
 */
@Service
@RequiredArgsConstructor
public class PhoneVerificationSendService {

  private static final Logger log = LoggerFactory.getLogger(PhoneVerificationSendService.class);

  private final PhoneVerificationService verifications;
  private final VerificationSmsSender sender;

  /**
   * 인증번호를 만들어 문자로 보내고 인증 ID를 돌려준다.
   *
   * @return verify 주소의 {@code {id}}
   * @throws BusinessException {@code SMS_SEND_FAILED}(업체가 실패로 답함), 그 밖에는 {@link
   *     PhoneVerificationService#issue}와 같다({@code RATE_LIMITED}·{@code DAILY_LIMIT}·{@code
   *     VALIDATION_FAILED})
   */
  public UUID send(String rawPhone, VerificationPurpose purpose) {
    IssuedCode issued = verifications.issue(rawPhone, purpose);
    VerificationSendResult result = sender.send(issued.phone(), issued.code());
    switch (result) {
      case SENT -> {}
      case FAILED -> {
        verifications.forgetFailedSend(issued.verificationId());
        log.warn("인증 문자 발송 실패: 인증 ID {}, 목적 {}", issued.verificationId(), purpose);
        throw new BusinessException(ErrorCode.SMS_SEND_FAILED);
      }
      case UNKNOWN ->
          log.warn("인증 문자 발송 결과 모름(보낸 것으로 봄): 인증 ID {}, 목적 {}", issued.verificationId(), purpose);
    }
    return issued.verificationId();
  }
}
