package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.rule.RequestKind;
import java.util.Objects;
import java.util.UUID;

/**
 * 응답 요청을 여는 명령. 원인 행동의 {@code StateStore.save} 안에서 넘긴다(09 3장 "기다림·통지").
 *
 * <p>상대의 연결 상태·문자 설정·가린 이름은 넘기지 않는다. notification이 {@code CounterpartReader}로 읽는다(decisions
 * 2026-11-17). 원인 행동 ID도 없다(저장 뒤에 생기고 05에 칸이 없음).
 */
public record OpenRequestCommand(
    UUID roomRecordId, RequestKind kind, RequestTarget target, PartySide requestedBySide) {

  public OpenRequestCommand {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedBySide, "requestedBySide");
  }
}
