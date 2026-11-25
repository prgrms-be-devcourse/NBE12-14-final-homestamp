package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.Recipient;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 번호 조회 가짜. 테스트가 정해 둔 번호를 돌려준다.
 *
 * <p>room·member의 실제 구현이 들어오면 그 PR에서 이 클래스를 지운다(같은 타입 빈이 둘이면 주입이 실패한다). 테스트는 test 코드의 {@code
 * InMemoryRecipientDirectory}를 쓴다.
 *
 * <p>{@link #resolve}는 정해 둔 결과만 돌려준다. 정해 두지 않은 자리를 {@code Unavailable}로 바꾸지 않고 예외를 낸다(계약에서 판정할 수 없는
 * 자리는 예외).
 */
@Service
public class FakeRecipientDirectory implements RecipientDirectory {

  private final Map<UUID, String> partyPhones = new ConcurrentHashMap<>();
  private final Map<UUID, String> memberPhones = new ConcurrentHashMap<>();
  private final Map<UUID, Recipient> recipients = new ConcurrentHashMap<>();
  private final Set<UUID> unresolvable = ConcurrentHashMap.newKeySet();

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

  @Override
  public Recipient resolve(UUID partyId) {
    Objects.requireNonNull(partyId, "partyId");
    if (unresolvable.contains(partyId)) {
      throw new IllegalStateException("자리 상태를 판정할 수 없어요(가짜에서 정한 모순)");
    }
    Recipient recipient = recipients.get(partyId);
    if (recipient == null) {
      throw new IllegalStateException("가짜에 정해 두지 않은 자리예요");
    }
    return recipient;
  }

  public void seedParty(UUID partyId, String phone) {
    partyPhones.put(partyId, phone);
  }

  public void seedMember(UUID memberId, String phone) {
    memberPhones.put(memberId, phone);
  }

  /** {@link #resolve}가 돌려줄 결과를 정한다. */
  public void seedRecipient(UUID partyId, Recipient recipient) {
    Objects.requireNonNull(partyId, "partyId");
    Objects.requireNonNull(recipient, "recipient");
    unresolvable.remove(partyId);
    recipients.put(partyId, recipient);
  }

  /** {@link #resolve}가 판정할 수 없는 자리(조회 오류·연결된 자리에 회원 없음 등)처럼 예외를 내게 한다. */
  public void seedUnresolvable(UUID partyId) {
    Objects.requireNonNull(partyId, "partyId");
    recipients.remove(partyId);
    unresolvable.add(partyId);
  }
}
