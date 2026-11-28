package com.chilsuitjo.back.room.service;

import java.time.Instant;
import java.util.UUID;

/**
 * 호실을 정산 완료로 바꾸는 창구(06 SET-5, 03 A "정산 끝내기"·"(자동) 삭제 기한 저장"). 정산(확인·혼자 끝내기)과 자동 마감(KEEP-2)이 부른다.
 *
 * <p>호실의 SETTLED는 정산 행동({@code CONFIRM_FINISH}·{@code FINISH_ALONE}·{@code AUTOCLOSE})의 결과다. 판단은
 * {@code SettlementTransitions}가 하고 호실 행동 기록({@code RoomTransitions})에는 그 전이를 두지 않는다(decisions
 * 2026-11-28).
 */
public interface RoomSettlement {

  /**
   * 호실을 정산 완료로 바꾸고 삭제 기한(완료 시각 + {@code retention.after-settled})을 저장한다. 호실을 잠근 트랜잭션 안에서 부른다.
   * {@code autoclose_due_at}은 남겨 둔다(자동 마감은 호실 상태로 거른다). 거주 종료가 아니면 부르는 쪽 버그라 {@code
   * IllegalStateException}이다.
   *
   * @param at 정산 완료가 된 행동의 시각. pipeline 안에서는 {@code ActionRequestContext.at()}, 자동 마감처럼 pipeline
   *     밖에서는 그 작업의 시각을 넘긴다
   */
  void settle(UUID roomRecordId, Instant at);
}
