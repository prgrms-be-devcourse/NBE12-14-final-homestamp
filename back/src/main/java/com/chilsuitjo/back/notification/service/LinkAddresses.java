package com.chilsuitjo.back.notification.service;

import java.util.Optional;
import java.util.UUID;

/**
 * 문자에 넣을 초대 링크 주소를 보낼 때 만드는 포트. notification이 두고 link(D)가 구현한다(#163 ③, decisions 2026-11-25 링크 토큰).
 * 문자 작업자만 부른다.
 *
 * <p>주소는 {@code <화면 Origin>/l#<토큰>}이다. 토큰은 링크 ID로 다시 만들고(03 6-1 "링크 토큰"), 돌려받은 주소는 그 문자 본문에만 넣는다.
 * DB·행동 기록·로그·예외 문구 어디에도 남기지 않는다(06 LINK-2b).
 */
public interface LinkAddresses {

  /**
   * 그 통지가 가리키는 초대 링크의 주소. 링크가 그 호실의 유효한 초대 링크({@code INVITE})이고 받는 자리가 통지의 받는 자리와 같으며, 저장된 해시가 지금
   * 키로 만든 토큰과 같을 때만 돌려준다. 하나라도 아니면 빈 값이다(다른 사람에게 갈 링크, 이미 닫힌 링크, 다른 키로 만든 링크를 넣지 않음). 판정할 수 없으면
   * 예외다.
   */
  Optional<String> inviteAddress(UUID roomRecordId, UUID linkId, UUID recipientPartyId);
}
