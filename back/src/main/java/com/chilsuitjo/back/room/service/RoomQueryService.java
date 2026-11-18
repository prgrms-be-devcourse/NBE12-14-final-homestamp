package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.room.dto.RoomPartSummary;
import java.util.UUID;

/** 다른 도메인이 호실 쪽 값을 읽는 창구(09 3장 "room ← 모두"). 권한 검사는 {@link RoomAccessService}가 맡는다. */
public interface RoomQueryService {

  /**
   * 이 호실 기록의 부위를 돌려준다. 없는 부위와 다른 호실의 부위는 모두 {@code NOT_FOUND}다(ROOM-2a: 남의 호실 부위는 있는지도 알 수 없다). 그래서
   * 권한 검사를 통과한 호실 ID를 넘기면, 요청 본문에 남의 부위 ID가 와도 막힌다. 숨긴 부위도 지난 기록이 가리키므로 돌려준다.
   */
  RoomPartSummary findPart(UUID roomRecordId, UUID roomPartId);
}
