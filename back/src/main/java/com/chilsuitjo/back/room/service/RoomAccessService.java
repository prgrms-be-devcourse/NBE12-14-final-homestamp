package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.room.dto.RoomAccess;
import java.util.UUID;

/** 호실 권한 검사. 모든 도메인이 요청을 처리하기 전에 부른다(09 3장 "room ← 모두"). */
public interface RoomAccessService {

  /** 이 회원이 이 호실의 당사자인지 확인한다. 당사자가 아니면 {@code NOT_FOUND}를 던진다(ROOM-2a). */
  RoomAccess require(UUID roomRecordId, UUID memberId);
}
