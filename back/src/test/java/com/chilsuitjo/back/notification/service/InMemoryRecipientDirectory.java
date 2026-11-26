package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.Recipient;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 번호 조회. 빈으로 등록하지 않아 main 빈(지금은 {@link FakeRecipientDirectory}, 나중에 room 실제 구현)과 부딪히지 않는다. 필요한
 * 테스트가 직접 만들거나, Spring 테스트면 그 테스트의 설정에서 {@code @Primary} 빈으로 넣는다.
 *
 * <p>{@link #resolve}는 정해 둔 결과만 돌려주고, 정해 두지 않은 자리는 예외다({@code Unavailable}로 바꾸지 않음). main 가짜가 지워져도
 * 아웃박스 테스트가 이것으로 그대로 돈다.
 */
public class InMemoryRecipientDirectory implements RecipientDirectory {

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

  /** 자리에 정해 둔 번호를 그대로 쓴다(지금 아웃박스 작업자가 부르는 메서드). */
  @Override
  public Optional<String> phoneOfRecipient(UUID partyId) {
    return phoneOfParty(partyId);
  }

  @Override
  public Recipient resolve(UUID partyId) {
    Objects.requireNonNull(partyId, "partyId");
    if (unresolvable.contains(partyId)) {
      throw new IllegalStateException("자리 상태를 판정할 수 없어요(테스트에서 정한 모순)");
    }
    Recipient recipient = recipients.get(partyId);
    if (recipient == null) {
      throw new IllegalStateException("테스트에서 정해 두지 않은 자리예요");
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

  /** 정해 둔 것을 모두 지운다. Spring 테스트에서 빈 하나를 여러 테스트가 같이 쓸 때 각 테스트 앞에서 부른다. */
  public void reset() {
    partyPhones.clear();
    memberPhones.clear();
    recipients.clear();
    unresolvable.clear();
  }
}
