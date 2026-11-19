package com.chilsuitjo.back.member.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 다른 도메인이 회원 쪽 값을 읽는 창구. 실명은 내보내지 않는다. */
public interface MemberQueryService {

  /**
   * 가린 이름(정서윤 → 정*윤, 06 ROOM-2c). 탈퇴한 회원이면 비어 있다(이름을 지웠으므로, 부르는 쪽이 "탈퇴한 임대인/임차인"으로 보인다). 없는 회원이면
   * {@code NOT_FOUND}를 던진다.
   */
  Optional<String> maskedName(UUID memberId);

  /**
   * 여러 회원의 가린 이름을 한 번에 읽는다(타임라인·확인 카드 목록처럼 행마다 이름을 보일 때). 값은 {@link #maskedName}과 같다: 탈퇴한 회원은 비어
   * 있고, 하나라도 없는 회원이 있으면 {@code NOT_FOUND}를 던진다. 같은 ID가 여러 번 와도 한 번만 담고, 빈 목록이면 빈 맵을 돌려준다.
   */
  Map<UUID, Optional<String>> maskedNames(Collection<UUID> memberIds);
}
