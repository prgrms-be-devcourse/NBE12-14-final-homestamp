package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.UUID;

/**
 * 거주 종료 행동의 결과(06 4-3 {@code move-out}·{@code move-out/confirm}·{@code move-out/reject}, ROOM-9).
 *
 * @param roomRecordId 호실 기록
 * @param status 호실의 지금 상태. 거주 종료가 됐으면 {@code ENDED}, 거주 종료 요청을 보냈거나 "아직 살고 있어요"면 {@code LIVING}. 같은
 *     키로 다시 온 요청이면 그사이 바뀐 상태일 수 있다
 * @param endRequested 집주인 측이 보낸 거주 종료 요청이 지금 열려 있나(기다림·미확인)
 */
public record MoveOutResult(UUID roomRecordId, RoomStatus status, boolean endRequested) {

  /**
   * 서비스 결과.
   *
   * @param body 응답 본문
   * @param replayed 같은 키로 다시 온 요청이라 새로 남긴 행동이 없는지(06 4-0)
   */
  public record Result(MoveOutResult body, boolean replayed) {}
}
