package com.chilsuitjo.back.notification.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 번호 조회 가짜. 테스트가 정해 둔 번호를 돌려준다.
 *
 * <p>room·member의 실제 구현이 들어오면 이 클래스를 지운다(같은 타입 빈이 둘이면 주입이 실패한다).
 */
@Service
public class FakeRecipientDirectory implements RecipientDirectory {

  private final Map<UUID, String> partyPhones = new ConcurrentHashMap<>();
  private final Map<UUID, String> memberPhones = new ConcurrentHashMap<>();

  @Override
  public Optional<String> phoneOfParty(UUID partyId) {
    return Optional.ofNullable(partyPhones.get(partyId));
  }

  @Override
  public Optional<String> phoneOfMember(UUID memberId) {
    return Optional.ofNullable(memberPhones.get(memberId));
  }

  /** 가짜는 자리에 정해 둔 번호를 그대로 쓴다. */
  @Override
  public Optional<String> phoneOfRecipient(UUID partyId) {
    return phoneOfParty(partyId);
  }

  public void seedParty(UUID partyId, String phone) {
    partyPhones.put(partyId, phone);
  }

  public void seedMember(UUID memberId, String phone) {
    memberPhones.put(memberId, phone);
  }
}
