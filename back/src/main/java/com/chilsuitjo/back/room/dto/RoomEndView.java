package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.notification.rule.RequestStatus;
import java.time.Instant;

/**
 * 호실 상세의 거주 종료 칸(06 4-3 {@code GET /api/rooms/{id}}, S-06 거주 종료 카드, ROOM-9).
 *
 * <p>거주 종료 요청과 그 답, 거주 종료 사실과 날짜는 공유를 그만둔 뒤에도 양쪽에 그대로 보인다(03 B "예외 둘", decisions 2026-11-17). 그래서
 * {@code asOf}로 거르지 않는다. 그 예외는 "내용이 날짜·사유뿐"이라서이므로, 요청의 혼자 진행 이유·닫힌 이유(상대 수신 거부·탈퇴·공유 중단 같은 상대 쪽
 * 사정)는 넣지 않는다. 화면이 그 값을 쓰게 되면 그 PR에서 노출 근거와 함께 더한다(#463 리뷰).
 */
public final class RoomEndView {

  private RoomEndView() {}

  /**
   * 집주인 측이 연 가장 최근 거주 종료 요청({@code END_REQUEST}). 닫힌 것도 준다.
   *
   * @param status 요청 상태({@code WAITING}·{@code UNCONFIRMED}·{@code ANSWERED}·{@code CLOSED} 등)
   * @param openedAt 요청을 연 시각(화면의 "○월 ○일에 이사했다고 남겼어요", "N일째")
   * @param dueAt 미확인이 되는 기한. 아직 첫 통지가 전달되지 않았으면 null이고, 그때는 남은 시간을 보이지 않는다(NOTI-4b)
   * @param rejectReason 세입자가 이 요청에 "아직 살고 있어요"로 답했으면 그 사유, 아니면 null
   */
  public record EndRequest(
      RequestStatus status, Instant openedAt, Instant dueAt, String rejectReason) {}
}
