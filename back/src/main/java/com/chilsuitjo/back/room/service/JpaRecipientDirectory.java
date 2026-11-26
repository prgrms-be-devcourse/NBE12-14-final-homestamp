package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.member.service.MemberQueryService;
import com.chilsuitjo.back.notification.dto.Recipient;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.repository.PartyRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RecipientDirectory}의 실제 구현(notification이 두고 room이 구현, decisions 2026-11-26, #100·#163).
 * 자리는 {@code state.party}에서, 회원 번호는 member의 인증 번호 조회({@code MemberQueryService.verifiedPhone})로
 * 읽는다. 읽기만 한다.
 *
 * <p>문자 방식이 {@code disabled}이면 켜지지 않는다. 그때는 {@code DisabledRecipientDirectory}가 늘 빈 값을 돌려 문자를 보내지
 * 않는다 (decisions 2026-11-25 운영 문자 모드). {@code fake}(local·test)와 {@code solapi}에서는 이 구현이 실제 번호를
 * 읽는다.
 *
 * <p>번호는 저장하거나 로그·예외 문구에 남기지 않는다(04 "개인정보 로그"). 예외 문구에는 자리 ID만 넣는다.
 */
@Service
@ConditionalOnExpression("!'${app.sms.mode:}'.equalsIgnoreCase('disabled')")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JpaRecipientDirectory implements RecipientDirectory {

  private final PartyRepository parties;
  private final MemberQueryService members;

  /** 회원의 인증 번호. 탈퇴했거나 인증이 풀렸으면 빈 값. */
  @Override
  public Optional<String> phoneOfMember(UUID memberId) {
    return members.verifiedPhone(memberId);
  }

  /**
   * 받을 자리의 지금 상태(#100 합의 다섯 결과). 자리가 없거나 연결된 자리에 회원이 없으면 판정할 수 없는 모순이라 예외를 그대로 올린다({@link
   * RecipientDirectory#resolve}).
   */
  @Override
  public Recipient resolve(UUID partyId) {
    Objects.requireNonNull(partyId, "partyId");
    Party party =
        parties
            .findById(partyId)
            .orElseThrow(() -> new IllegalStateException("통지를 받을 자리가 없어요: " + partyId));
    return switch (party.getStatus()) {
      case CONNECTED, SHARING_STOPPED -> member(party);
      case INVITED ->
          party
              .invitePhoneForSending()
              .<Recipient>map(Recipient.Invitee::new)
              .orElseGet(Recipient.InviteeWithoutPhone::new);
      case EMPTY, NUMBER_ONLY, REJECTED, WITHDRAWN -> new Recipient.Unavailable();
    };
  }

  private Recipient member(Party party) {
    UUID memberId = party.getMemberId();
    if (memberId == null) {
      throw new IllegalStateException("연결된 자리에 회원이 없어요: " + party.getId());
    }
    return members
        .verifiedPhone(memberId)
        .filter(phone -> !phone.isBlank())
        .<Recipient>map(phone -> new Recipient.VerifiedMember(memberId, phone))
        .orElseGet(() -> new Recipient.UnverifiedMember(memberId));
  }
}
