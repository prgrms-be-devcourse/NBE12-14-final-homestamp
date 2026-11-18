package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.room.dto.RoomPartSummary;
import java.util.UUID;

/** 다른 도메인이 호실 쪽 값을 읽는 창구(09 3장 "room ← 모두"). 권한 검사는 {@link RoomAccessService}가 맡는다. */
public interface RoomQueryService {

  /** 부위가 속한 호실 기록과 부위 이름을 돌려준다. 없는 부위면 {@code NOT_FOUND}를 던진다. */
  RoomPartSummary findPart(UUID roomPartId);
}
