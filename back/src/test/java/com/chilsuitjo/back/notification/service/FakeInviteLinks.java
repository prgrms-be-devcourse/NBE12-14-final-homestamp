package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * DB 없는 테스트용 {@link InviteLinks}(#163 ②). 실제 구현({@code InviteLinkService})과 같게 같은 호실·자리·기록의 유효한 링크는
 * 다시 쓰고, 무효로 한 링크는 처음 이유를 바꾸지 않으며, 자리 단위 무효는 이미 무효였던 것까지 그 자리의 링크 ID를 전부 돌려준다. 잠금·트랜잭션·DB 외래 키는 흉내
 * 내지 않는다(그건 실제 DB 테스트에서 본다).
 *
 * <p>쓰는 법: room 규칙·서비스 단위 테스트에 넣고 {@link #issued()}로 무엇을 발급했는지 본다. 스프링 테스트는 실제 구현을 그대로 쓴다.
 */
public class FakeInviteLinks implements InviteLinks {

  private final List<Issued> issued = new ArrayList<>();

  @Override
  public UUID issueInvite(
      UUID roomRecordId, UUID recipientPartyId, RequestTarget record, UUID issuedByMemberId) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(recipientPartyId, "recipientPartyId");
    Objects.requireNonNull(issuedByMemberId, "issuedByMemberId");
    return issued.stream()
        .filter(link ->
            link.invalidReason == null
                && link.roomRecordId.equals(roomRecordId)
                && link.partyId.equals(recipientPartyId)
                && Objects.equals(link.record, record))
        .findFirst()
        .map(link -> link.id)
        .orElseGet(() -> {
          Issued link =
              new Issued(
                  UUID.randomUUID(), roomRecordId, recipientPartyId, record, issuedByMemberId);
          issued.add(link);
          return link.id;
        });
  }

  @Override
  public int invalidateInvites(UUID roomRecordId, LinkInvalidReason reason) {
    return invalidate(roomRecordId, null, reason);
  }

  @Override
  public List<UUID> invalidateInvites(UUID roomRecordId, UUID partyId, LinkInvalidReason reason) {
    Objects.requireNonNull(partyId, "partyId");
    invalidate(roomRecordId, partyId, reason);
    // 실제 구현처럼 이미 무효였던 것까지 그 자리의 초대 링크 ID 전부(가짜는 정산 초대 링크를 만들지 않는다)
    return issued.stream()
        .filter(link -> link.roomRecordId.equals(roomRecordId) && link.partyId.equals(partyId))
        .map(link -> link.id)
        .toList();
  }

  /** 발급한 링크 전부(무효 포함), 발급한 순서대로. */
  public List<Issued> issued() {
    return List.copyOf(issued);
  }

  private int invalidate(UUID roomRecordId, UUID partyId, LinkInvalidReason reason) {
    Objects.requireNonNull(reason, "reason");
    int count = 0;
    for (Issued link : issued) {
      if (link.invalidReason == null
          && link.roomRecordId.equals(roomRecordId)
          && (partyId == null || link.partyId.equals(partyId))) {
        link.invalidReason = reason;
        count++;
      }
    }
    return count;
  }

  /** 발급한 링크 하나. {@code invalidReason}이 null이면 유효하다. */
  public static final class Issued {
    private final UUID id;
    private final UUID roomRecordId;
    private final UUID partyId;
    private final RequestTarget record;
    private final UUID issuedByMemberId;
    private LinkInvalidReason invalidReason;

    private Issued(
        UUID id, UUID roomRecordId, UUID partyId, RequestTarget record, UUID issuedByMemberId) {
      this.id = id;
      this.roomRecordId = roomRecordId;
      this.partyId = partyId;
      this.record = record;
      this.issuedByMemberId = issuedByMemberId;
    }

    public UUID id() {
      return id;
    }

    public UUID roomRecordId() {
      return roomRecordId;
    }

    public UUID partyId() {
      return partyId;
    }

    public RequestTarget record() {
      return record;
    }

    public UUID issuedByMemberId() {
      return issuedByMemberId;
    }

    public LinkInvalidReason invalidReason() {
      return invalidReason;
    }
  }
}
