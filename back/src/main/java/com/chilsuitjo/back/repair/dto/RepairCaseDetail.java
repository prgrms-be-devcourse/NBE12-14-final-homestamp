package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import com.chilsuitjo.back.repair.rule.ClosedBy;
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
 * @param closedBy 처리 완료면 누가 확인해 끝났나(양측 / 한쪽: 화면의 "처리 완료(○○ 확인)"). 처리 완료가 아니면 null
 * @param closeObjection 혼자 끝낸 처리 완료에 상대가 남긴 이견(06 REP-5f "○○ 님 이견(날짜, 사유)"). 상태는 바꾸지 않는다. 혼자 끝낸 처리
 *     완료가 아니거나(양측 확인이 되면 이견은 이력) 이견이 없으면 null
 * @param solo 혼자 진행 가능으로 바뀐 수리 건인지(ROOM-7)
 * @param createdAt 등록한 시각
 * @param asOf 공유를 그만둬 보이는 범위를 고정한 시각(화면의 "○월 ○일 기준", 03 6-6 9번). 이때 {@code status}·{@code
 *     content}·{@code defect}·{@code money}는 그 시각까지의 행동과 보는 사람 자신의 행동으로 다시 계산한 값이다. 고정되지 않았으면 null.
 *     상대가 탈퇴했으면 남은 공유 중단 시각이 있어도 null이고 상태는 지금 값이다(06 CONS-3p, 호실 상세와 같음). 이때는 중단 동안 탈퇴자가 남긴 내용(원인
 *     설명·진행 상황·고친 내용·사진)만 가린다(03 6-4, {@code RepairSight})
 * @param content 보이는 판(고정되지 않았으면 지금 판)
 * @param defect 하자 인정
 * @param money 수리비 부담
 * @param photos 수리 건에 붙은 사진(하자 등록·보충, {@code sortOrder} 순). 보는 사람이 볼 수 있는 것만이고 지운 사진은 흔적({@code
 *     erased})으로 남는다(03 3-1, 불변식 16). 주소는 {@code POST /api/photos/view-urls}로 따로 받는다
 * @param causeNotes 원인 설명(06 REP-8). 측마다 지금 설명 하나이고 고치기 전 설명은 {@code previous}에 접혀 있다. 공유를 그만둔 뒤에는
 *     {@code asOf}까지의 설명·응답과 보는 사람 자신의 것만 보인다(03 6-6 9번)
 * @param progressNotes 진행 상황(06 REP-6, 남긴 순서). 공유를 그만둔 뒤에는 그 시각까지 남긴 것과 보는 사람 자신의 것만 보인다(03 6-6 9번)
 * @param corrections 내용(부위·제목·설명)의 고친 내용(06 REP-7, 만든 순서). 기다림·보류·이견은 앞모습 아래 상자에, 확인됨·거둠·대신됨·닫힘은
 *     [고친 이력 ▸]에 그린다(03 G "화면 규칙 네 줄"). 공유를 그만둔 뒤에는 {@code asOf}까지 고친 것과 보는 사람 자신이 고친 것만 보이고, 그 뒤
 *     상대의 응답은 드러나지 않는다(03 6-6 9번)
 * @param availableActions 지금 이 사람이 누를 수 있는 행동. 이름은 API 끝 단어와 같다(06 4-0 "할 수 있는 행동"). 원인 설명 응답은 설명마다
 *     {@code CauseNote.canRespond}로, 고친 내용 응답·거두기는 고친 내용마다 {@code Correction.availableActions}로 준다.
 *     [모두 확인]은 {@code accept-all}
 */
public record RepairCaseDetail(
    UUID id,
    UUID roomRecordId,
    PartySide mySide,
    PartySide registeredBy,
    RepairStatus status,
    PartySide closeRequestedBy,
    ClosedBy closedBy,
    CloseObjection closeObjection,
    boolean solo,
    Instant createdAt,
    Instant asOf,
    Content content,
    Defect defect,
    Money money,
    List<PhotoCard> photos,
    List<CauseNote> causeNotes,
    List<ProgressNote> progressNotes,
    List<Correction> corrections,
    List<String> availableActions) {

  /**
   * 고친 내용 하나(03 G). 상대 화면은 앞모습({@code content})과 새 값을 비교해 바뀐 글자만 표시한다(REP-7f).
   *
   * @param field 칸({@code roomPartId}·{@code title}·{@code description})
   * @param newValue 새 값. 부위면 부위 ID, 설명을 비우면 null
   * @param newPartName 부위를 고쳤으면 새 부위 이름. 그 밖에는 null
   * @param requestedBy 고친 측
   * @param reason 고친 이유
   * @param status 기다림 / 확인됨 / 거절(보류·이견) / 미확인 / 거둠 / 대신됨 / 닫힘
   * @param answer 상대의 지금 응답(확인 전이면 {@code PENDING}, 거절이면 {@code NEED_MORE}·{@code DISPUTE})
   * @param answerReason 보류·이견의 사유. 그 밖에는 null
   * @param answeredAt 마지막 응답 시각. 없으면 null
   * @param closedReason 닫혔으면 이유(예: {@code CLOSED_BY_BOTH} "처리 완료로 닫힘")
   * @param photos 다시 보낼 때 더한 근거 사진(대상 {@code CORRECTION_REQUEST}, 단계 {@code BEFORE}). 처음 고친 내용이면 빈
   *     목록
   * @param availableActions 이 고친 내용에 지금 누를 수 있는 행동({@code respond}·{@code withdraw}·{@code
   *     resubmit})
   */
  public record Correction(
      UUID id,
      String field,
      Object newValue,
      String newPartName,
      PartySide requestedBy,
      String reason,
      CorrectionStatus status,
      AckState answer,
      String answerReason,
      Instant answeredAt,
      ClosedReason closedReason,
      Instant createdAt,
      List<PhotoCard> photos,
      List<String> availableActions) {}

  /**
   * 진행 상황 하나(06 REP-6). 상태는 바꾸지 않는 기록이라 고칠 수 없다(판이 하나).
   *
   * @param side 남긴 측
   * @param text 글. 사진만 남겼으면 빈 글자
   * @param asksCheck [상황이 바뀌었어요]를 켜서 상대에게 확인을 요청했는지(REP-6d)
   * @param photos 진행 상황 사진(단계 {@code PROGRESS})
   * @param response 상대의 지금 답. [상황이 바뀌었어요]가 아니거나 아직 답이 없으면 null
   * @param canRespond 보는 사람이 답할 수 있는지(상황 변화 확인 요청이고 상대 측이며 맞아요가 아님, {@code POST
   *     /api/progress-notes/{id}/respond})
   */
  public record ProgressNote(
      UUID id,
      PartySide side,
      String text,
      boolean asksCheck,
      Instant createdAt,
      List<PhotoCard> photos,
      ProgressResponse response,
      boolean canRespond) {}

  /**
   * [상황이 바뀌었어요]로 남긴 진행 상황에 상대가 한 지금 답(06 REP-6d). 보는 사람에게 보이는 응답 중 마지막 것이다.
   *
   * @param answer 맞아요 / 보류 / 이견
   * @param reason 보류·이견의 사유. 맞아요면 null
   * @param at 답한 시각
   */
  public record ProgressResponse(AckState answer, String reason, Instant at) {}

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
   * 혼자 끝낸 건에 남은 이견(마지막 [아직 안 됐어요], 행동 기록 {@code NOT_YET}이 원본).
   *
   * @param side 이견을 남긴 측(혼자 끝낸 측의 상대)
   * @param reason 사유
   * @param at 남긴 시각
   */
  public record CloseObjection(PartySide side, String reason, Instant at) {}

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
