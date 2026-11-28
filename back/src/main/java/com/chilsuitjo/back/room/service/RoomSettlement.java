package com.chilsuitjo.back.room.service;

import java.util.UUID;

/**
 * 호실을 정산 완료로 바꾸는 창구(06 SET-5, 03 A "정산 끝내기"·"(자동) 삭제 기한 저장"). 정산(확인·혼자 끝내기)과 자동 마감(KEEP-2)이 부른다.
 */
public interface RoomSettlement {

  /**
   * 호실을 정산 완료로 바꾸고 삭제 기한(완료 시각 + {@code retention.after-settled})을 저장한다. pipeline이 호실을 잠근 뒤({@code
   * StateStore.save} 안) 부른다. 시각은 행동 시각이다. 거주 종료가 아니면 부르는 쪽 버그라 {@code IllegalStateException}이다.
   */
  void settle(UUID roomRecordId);
}
