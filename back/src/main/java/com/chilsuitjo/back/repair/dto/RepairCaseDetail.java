package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import com.chilsuitjo.back.repair.rule.MoneyStatus;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 수리 건 상세({@code GET /api/repair-cases/{id}}, 06 4-6·S-12).
 *
 * <p>영역마다 칸을 나눠 둔다. 지금은 기본 정보·내용·하자 인정·부담 상태·수리 건 사진만 있고, 원인 설명·진행 상황·경과·통지 상태는 그 기능을 만드는 PR에서 칸을
 * 더한다(이미 있는 칸은 바꾸지 않는다, #164).
 *
 * @param id 수리 건
 * @param roomRecordId 호실 기록
 * @param mySide 보는 사람의 측
 * @param registeredBy 등록한 측. 하자에 답하는 쪽은 그 반대 측이다
 * @param status 수리 진행(시작 전 → 처리 중 → 종료 확인 대기 → 처리 완료)
 * @param closeRequestedBy 종료 확인 대기면 종료를 요청한 측(화면의 "○○ 쪽이 종료를 요청했어요"). 종료 확인 대기가 아니면 null
 * @param solo 혼자 진행 가능으로 바뀐 수리 건인지(ROOM-7)
 * @param createdAt 등록한 시각
 * @param asOf 공유를 그만둬 보이는 범위를 고정한 시각(화면의 "○월 ○일 기준", 03 6-6 9번). 이때 {@code status}·{@code
 *     content}·{@code defect}·{@code money}는 그 시각까지의 행동과 보는 사람 자신의 행동으로 다시 계산한 값이다. 고정되지 않았으면 null
 * @param content 보이는 판(고정되지 않았으면 지금 판)
 * @param defect 하자 인정
 * @param money 수리비 부담
 * @param photos 수리 건에 붙은 사진(하자 등록·보충, {@code sortOrder} 순). 보는 사람이 볼 수 있는 것만이고 지운 사진은 흔적({@code
 *     erased})으로 남는다(03 3-1, 불변식 16). 주소는 {@code POST /api/photos/view-urls}로 따로 받는다
 * @param causeNotes 원인 설명(06 REP-8). 측마다 지금 설명 하나이고 고치기 전 설명은 {@code previous}에 접혀 있다. 공유를 그만둔 뒤에는
 *     {@code asOf}까지의 설명·응답과 보는 사람 자신의 것만 보인다(03 6-6 9번)
 * @param availableActions 지금 이 사람이 누를 수 있는 행동. 이름은 API 끝 단어와 같다(06 4-0 "할 수 있는 행동"). 원인 설명 응답은 설명마다
 *     {@code CauseNote.canRespond}로 준다
 */
public record RepairCaseDetail(
    UUID id,
    UUID roomRecordId,
    PartySide mySide,
    PartySide registeredBy,
    RepairStatus status,
    PartySide closeRequestedBy,
    boolean solo,
    Instant createdAt,
    Instant asOf,
    Content content,
    Defect defect,
    Money money,
    List<PhotoCard> photos,
    List<CauseNote> causeNotes,
    List<String> availableActions) {

  /**
   * 원인 설명 하나(03 D-2 "원인에 대한 설명"). 화면은 "세입자의 설명 · 근거: 내 추측"처럼 보이고 "원인 확인됨"·"책임 있음"은 쓰지 않는다.
   *
   * @param side 남긴 측
   * @param basis 근거 종류(내 추측 / 직접 봤어요 / 업체·전문가 소견 / 다른 사람에게 들었어요)
   * @param expertName 업체·전문가 이름. 없으면 null
   * @param previousId 이 설명이 고친 이전 설명. 처음 설명이면 null. 고치려면 지금 설명의 {@code id}를 {@code previousId}로 보낸다
   * @param response 상대 응답. 아직 없으면 null("○○ 응답 전")
   * @param photos 근거 사진(단계 {@code BASIS})
   * @param canRespond 보는 사람이 이 설명에 응답할 수 있는지(지금 설명이고 상대 측이며 맞아요가 아님)
   * @param previous 고치기 전 설명들(새것부터). 지금 설명에만 있고 이전 설명에서는 빈 목록이다
   */
  public record CauseNote(
      UUID id,
      PartySide side,
      String text,
      CauseBasis basis,
      String expertName,
      UUID previousId,
      Instant createdAt,
      CauseResponse response,
      List<PhotoCard> photos,
      boolean canRespond,
      List<CauseNote> previous) {}

  /**
   * 원인 설명에 대한 응답. 그 설명에만 붙고 고친 새 설명에는 붙지 않는다(REP-8c).
   *
   * @param answer 맞아요 / 보류 / 이견
   * @param reason 보류·이견의 사유. 맞아요면 null
   */
  public record CauseResponse(AckState answer, String reason, Instant at) {}

  /**
   * 지금 판({@code repair_content}).
   *
   * @param no 판 번호. 하자에 답할 때 {@code seenVersionNo}로 보낸다
   * @param partName 부위 이름(지금 이름. 판에는 부위 ID만 남는다)
   * @param description 설명. 없으면 null
   */
  public record Content(
      int no, UUID roomPartId, String partName, String title, String description) {}

  /**
   * 하자 인정.
   *
   * @param ack 확인 기다림 / 맞아요 / 보류 / 이견 / 확인 없음
   * @param nextRerequestAt 다음에 보충할 수 있는 시각("○월 ○일부터 다시 요청할 수 있어요"). 보충한 적이 없으면 null
   */
  public record Defect(AckState ack, Instant nextRerequestAt) {}

  /**
   * 수리비 부담. 지불·부담 제안은 그 기능을 만들 때 칸을 더한다.
   *
   * @param status 미정 / 확인 기다림 / 합의 / 이견 / 비용 없음
   */
  public record Money(MoneyStatus status) {}
}
