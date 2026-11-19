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
}
