package com.chilsuitjo.back.member.service;

import java.util.Optional;
import java.util.UUID;

/** 다른 도메인이 회원 쪽 값을 읽는 창구. 실명은 내보내지 않는다. */
public interface MemberQueryService {

  /**
   * 가린 이름(정서윤 → 정*윤, 06 ROOM-2c). 탈퇴한 회원이면 비어 있다(이름을 지웠으므로, 부르는 쪽이 "탈퇴한 임대인/임차인"으로 보인다). 없는 회원이면
   * {@code NOT_FOUND}를 던진다.
   */
  Optional<String> maskedName(UUID memberId);
}
