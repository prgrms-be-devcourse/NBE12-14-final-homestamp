package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.time.Instant;
import java.util.Objects;

/**
 * 응답 요청 하나를 읽은 값({@code WaitQuery}, #266). 요청 ID나 다른 칸은 필요한 화면이 생길 때만 더한다.
 *
 * @param kind 요청 종류
 * @param target 요청 대상(호실 기록이면 {@code ROOM_RECORD})
 * @param requestedBySide 요청한 측. 답할 측은 그 상대다
 * @param status 지금 상태
 * @param openedAt 요청을 처음 만든 시각({@code created_at}). {@code restart}로 묶여도 바뀌지 않는다(다시 세는 기한의 기준과 다른
 *     값)
 * @param firstNotifiedAt 첫 통지가 전달되거나 열린 시각. 아직 없으면 null. 번호를 고쳐 새 번호에 닿기를 기다리는 중에도 옛 번호 시각이 남으므로
 *     기다림 시작 신호가 아니다(기한은 {@code dueAt})
 * @param dueAt 미확인이 되는 기한({@code unconfirmed_due_at}). 실제로 {@code UNCONFIRMED}가 된 시각이 아니다. null이면
 *     기한이 아직 정해지지 않았다는 뜻일 뿐, 알림이 보내졌거나 전달됐다는 보장은 없다
 * @param soloReason 혼자 진행이 열린 이유({@code solo_reason}, "확인 없음 · 이유" 화면). 없으면 null
 * @param closedReason {@code CLOSED}일 때만 닫힌 이유, 아니면 null
 * @param closedAt {@code CLOSED}일 때만 닫힌 시각, 아니면 null
 */
public record RequestView(
    RequestKind kind,
    RequestTarget target,
    PartySide requestedBySide,
    RequestStatus status,
    Instant openedAt,
    Instant firstNotifiedAt,
    Instant dueAt,
    SoloReason soloReason,
    ClosedReason closedReason,
    Instant closedAt) {

  public RequestView {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedBySide, "requestedBySide");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(openedAt, "openedAt");
    if ((status == RequestStatus.CLOSED) != (closedReason != null)) {
      throw new IllegalArgumentException("닫힌 이유는 CLOSED일 때만 있어요");
    }
    if ((status == RequestStatus.CLOSED) != (closedAt != null)) {
      throw new IllegalArgumentException("닫힌 시각은 CLOSED일 때만 있어요");
    }
  }

  /** 이 요청에 답할 측(요청한 측의 상대, 06 4-6 {@code answeringSide}). */
  public PartySide answeringSide() {
    return requestedBySide == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
  }
}
