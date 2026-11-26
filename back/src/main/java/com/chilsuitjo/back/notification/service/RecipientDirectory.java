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

  /** 비회원 자리의 초대 번호(05 {@code party.invite_phone}). 지워졌으면(연결·수신 거부·탈퇴) 빈 값. */
  Optional<String> phoneOfParty(UUID partyId);

  /** 회원의 인증된 번호. 탈퇴했거나 인증이 풀렸으면 빈 값. */
  Optional<String> phoneOfMember(UUID memberId);

  /**
   * 통지를 보낼 자리의 지금 번호. 연결된 회원 자리면 그 회원의 인증된 번호, 아직 비회원 자리면 초대 번호다. 보낼 수 없으면(탈퇴·인증 풀림·번호 지움) 빈 값이고,
   * 아웃박스 작업자는 문자를 보내지 않는다(통지를 최종 실패로 두지 않음, 04 4-2).
   */
  Optional<String> phoneOfRecipient(UUID partyId);

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
   * <p>지금 부르는 곳은 없다. 아웃박스 작업자는 room 실제 구현이 들어온 뒤 이것으로 바꾸고, 그때 {@link #phoneOfParty}·{@link
   * #phoneOfRecipient}를 지운다({@link #phoneOfMember}는 남김).
   */
  Recipient resolve(UUID partyId);
}
