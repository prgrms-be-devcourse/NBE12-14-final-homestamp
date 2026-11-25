package com.chilsuitjo.back.room.service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 호실 입주일을 읽고 비어 있을 때 채우는 창구(09 3장 "room ← 모두"). 입주 점검 시작이 입주일을 묻고 같은 트랜잭션에서 저장할 때 쓴다(06 4-7 {@code
 * POST /api/rooms/{id}/inspections}, INSP-2b).
 *
 * <p>입주일을 채운 사실의 원본은 그 행동 기록(입주 점검 시작의 payload {@value #FILLED_PAYLOAD_KEY})이다. 호실 상세는 이 기록으로 "공유를
 * 그만둔 뒤 상대가 채운 입주일"을 가린다(불변식 18, #190).
 */
public interface RoomMoveInDates {

  /** 입주일을 채운 행동이 payload에 남기는 키. 값은 ISO 날짜 글자({@code 2026-03-01})다. */
  String FILLED_PAYLOAD_KEY = "moveInDate";

  /** 지금 입주일. 비어 있으면 빈 값이다. 다른 호실이거나 없는 호실이면 {@code NOT_FOUND}다. */
  Optional<LocalDate> find(UUID roomRecordId);

  /**
   * 비어 있는 입주일을 채운다. pipeline이 호실을 잠근 뒤({@code StateStore.save} 안) 부른다. 이미 입주일이 있으면 부르는 쪽 버그라 {@code
   * IllegalStateException}이다(바꾸기는 고치기, 03 G).
   */
  void fill(UUID roomRecordId, LocalDate date);
}
