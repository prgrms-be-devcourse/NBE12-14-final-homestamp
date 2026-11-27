package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.Recipient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 문자 발송이 꺼진 상태({@code app.sms.mode=disabled})의 번호 조회. 번호는 언제나 빈 값이다(지어낸 번호를 돌려주지 않음). 아웃박스 작업자는
 * disabled에서 받는 사람을 읽지 않고 문자 작업을 보내지 않은 채 끝내므로(다시 보내지 않음), 꺼진 동안의 통지가 나중에 한꺼번에 나가지 않는다.
 *
 * <p>실제 번호 조회(room의 {@code JpaRecipientDirectory})는 disabled가 아닐 때만 켜진다. disabled에서는 이 구현이 그대로 남아
 * 번호를 주지 않는다(decisions 2026-11-25 운영 문자 모드, #163).
 */
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "disabled")
public class DisabledRecipientDirectory implements RecipientDirectory {

  @Override
  public Optional<String> phoneOfMember(UUID memberId) {
    return Optional.empty();
  }

  /**
   * 이 구현은 문자 발송만 비활성화하고 실제 자리·회원 상태는 읽지 않는다. 그 상태를 {@link Recipient.Unavailable}로 꾸며내지 않는다. 아웃박스
   * 작업자는 disabled에서 이것을 부르지 않는다. 불리면 개발 오류다.
   */
  @Override
  public Recipient resolve(UUID partyId) {
    throw new UnsupportedOperationException("disabled 문자 모드에서는 수신자 상태를 조회하지 않는다");
  }
}
