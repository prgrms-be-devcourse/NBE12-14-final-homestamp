package com.chilsuitjo.back.notification.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 문자 발송이 꺼진 상태({@code app.sms.mode=disabled})의 번호 조회. 언제나 빈 값이다(지어낸 번호를 돌려주지 않음). 아웃박스 작업자는 번호가 없으면
 * 문자를 보내지 않고 그 문자 작업을 끝내므로(다시 보내지 않음), 꺼진 동안의 통지가 나중에 한꺼번에 나가지 않는다.
 *
 * <p>실제 번호 조회(room·member, A)가 들어오면 그 구현이 이 자리를 대신한다.
 */
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "disabled")
public class DisabledRecipientDirectory implements RecipientDirectory {

  @Override
  public Optional<String> phoneOfParty(UUID partyId) {
    return Optional.empty();
  }

  @Override
  public Optional<String> phoneOfMember(UUID memberId) {
    return Optional.empty();
  }

  @Override
  public Optional<String> phoneOfRecipient(UUID partyId) {
    return Optional.empty();
  }
}
