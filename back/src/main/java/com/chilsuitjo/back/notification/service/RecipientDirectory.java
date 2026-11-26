package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.Recipient;
import java.util.Optional;
import java.util.UUID;

/**
 * 문자를 보내기 직전에만 받는 사람 번호를 읽는 포트. notification이 두고 room·member(A)가 구현한다(D 초안 2-3).
 *
 * <p>번호는 저장하거나 로그·예외 문구에 남기지 않는다(04 "개인정보 로그"). 상대 상태 조회({@link CounterpartReader})와 목적·권한이 달라 합치지
 * 않는다.
 */
public interface RecipientDirectory {

  /** 회원의 인증된 번호. 탈퇴했거나 인증이 풀렸으면 빈 값. */
  Optional<String> phoneOfMember(UUID memberId);

  /**
   * 통지를 받을 자리가 지금 누구인지와 알려진 번호(#100 A·D 합의). 보낼 때 읽으므로 번호를 고친 뒤에는 새 번호가 나온다.
   *
   * <ul>
   *   <li>연결된 회원 자리({@code CONNECTED}·{@code SHARING_STOPPED}): 인증된 번호가 있으면 {@link
   *       Recipient.VerifiedMember}, 인증이 풀렸으면 {@link Recipient.UnverifiedMember}
   *   <li>초대 자리({@code INVITED}): 초대 번호가 있으면 {@link Recipient.Invitee}, 지워졌으면 {@link
   *       Recipient.InviteeWithoutPhone}
   *   <li>그 밖({@code EMPTY}·{@code NUMBER_ONLY}·{@code REJECTED}·{@code WITHDRAWN} 등): {@link
   *       Recipient.Unavailable}
   * </ul>
   *
   * <p>상태를 정상적으로 판정할 수 없으면 {@code Unavailable}로 바꾸지 않고 예외를 그대로 올린다(DB 조회 오류, 연결된 자리에 회원이 없는 모순 등).
   * 통지는 외래 키로 있는 자리만 가리키므로({@code fk_notice_recipient_party_id_room}) 자리가 없는 것도 모순이다. 번호는 로그·예외 문구에
   * 남기지 않는다.
   *
   * <p>아웃박스 작업자는 문자를 보내기 직전에 이것만 부른다. 번호가 있는 {@code VerifiedMember}·{@code Invitee}에만 보내고, 예외면 보내지
   * 않고 다시 시도로 둔다. 옛 {@code phoneOfParty}·{@code phoneOfRecipient}는 작업자를 이것으로 바꾸며 지웠다(#163 1-3 순서
   * 4). {@code app.sms.mode=disabled}에서는 작업자가 이것을 부르지 않는다.
   */
  Recipient resolve(UUID partyId);
}
