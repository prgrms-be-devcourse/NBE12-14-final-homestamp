package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.room.dto.RoomForLink;
import java.util.Optional;
import java.util.UUID;

/**
 * 링크 열람에서 호실 쪽 값을 읽는 창구(09 3장 "room ← 모두", #303, #337). {@code link}의 {@code LinkContentSource}가
 * 부른다.
 *
 * <p>{@link RoomQueryService}는 권한 검사를 통과한 호실을 받는 창구라 따로 둔다. 링크 열람자는 회원이 아니므로 {@link
 * RoomAccessService}를 거치지 않는다. 토큰 확인과 링크가 이 호실·자리의 것인지는 {@code link}가 먼저 본다.
 */
public interface RoomLinkQuery {

  /**
   * 이 호실의 이 자리에서 본 호실. 없는 호실이거나 그 호실의 자리가 아니면 빈 값이다(예외를 던지지 않는다). 거주 종료·정산 완료·합류 전 기록인 호실도 돌려주고, 보여
   * 줄지는 부르는 쪽이 정한다.
   */
  Optional<RoomForLink> forLink(UUID roomRecordId, UUID partyId);
}
