package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.MoneyStatus;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 호실의 수리 건 목록({@code GET /api/rooms/{id}/repair-cases}, 06 4-6, S-06 수리 탭). 칸 이름과 값은 상세({@link
 * RepairCaseDetail})와 같다.
 *
 * @param asOf 공유를 그만둬 보이는 범위를 고정한 시각(03 6-6 9번). 고정되지 않았으면 null. 모든 줄에 같다
 * @param mySide 보는 사람의 측. 줄의 {@code waiting.answeringSide}와 비교해 "내 차례"를 정한다
 * @param items 새것부터(등록 시각, 같으면 ID)
 * @param nextCursor 다음 쪽 커서(이번 쪽 마지막 수리 건 ID). 더 없으면 null
 */
public record RepairCaseList(Instant asOf, PartySide mySide, List<Item> items, String nextCursor) {

  /**
   * 수리 건 한 줄. {@code availableActions}·사진·진행 상황·원인 설명은 상세에서 준다(06 4-6).
   *
   * @param lastActionAt 보는 사람이 볼 수 있는 이 건의 마지막 당사자 행동 시각(화면의 "N일째"·홈의 오래 멈춘 건, 03 G "멈춘 기간", 6-6
   *     9번). 이 건의 행동은 수리 건·그 건의 진행 상황·원인 설명·고친 내용이 대상인 행동이다. 등록도 행동이라 비지 않는다
   * @param waiting 이 건에 열린 응답 요청(06 4-6). <b>지금은 늘 빈 목록</b>이다: 응답 요청 조회 포트({@code WaitQuery},
   *     #266)가 들어오면 채운다(#283)
   */
  public record Item(
      UUID id,
      PartySide registeredBy,
      Instant createdAt,
      Content content,
      RepairStatus status,
      Instant lastActionAt,
      PartySide closeRequestedBy,
      ClosedBy closedBy,
      Defect defect,
      Money money,
      List<Waiting> waiting) {}

  /**
   * 보이는 판의 요약.
   *
   * @param no 판 번호
   * @param partName 부위 이름(지금 이름)
   */
  public record Content(int no, String partName, String title) {}

  /** 하자 인정. */
  public record Defect(AckState ack) {}

  /** 수리비 부담. */
  public record Money(MoneyStatus status) {}

  /**
   * 열린 응답 요청 하나({@code WAITING}·{@code UNCONFIRMED}, 06 4-6).
   *
   * @param answeringSide 답할 측. 보는 사람의 측이면 "내 차례"
   * @param dueAt 미확인 기한. 아직 정해지지 않았으면 null
   * @param soloReason 확인 없음의 이유({@code response_request.solo_reason}). 없으면 null
   */
  public record Waiting(
      RequestKind kind,
      PartySide answeringSide,
      RequestStatus status,
      Instant dueAt,
      String soloReason) {}
}
