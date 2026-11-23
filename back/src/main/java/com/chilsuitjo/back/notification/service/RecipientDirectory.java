package com.chilsuitjo.back.notification.service;

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
}
