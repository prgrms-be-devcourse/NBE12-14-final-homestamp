package com.chilsuitjo.back.inspection.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 점검({@code GET /api/inspections/{id}}, 06 4-7·S-14).
 *
 * <p>작성 중인 점검을 기준 측이 아닌 사람이 보면 "작성 중"만 보인다(06 INSP-1a): {@code items}가 null이다. 화면은 {@code
 * authorSide}로 "○○이 기준 기록을 쓰고 있어요"를 보여 준다. 적지 않은 부위는 항목이 없다(화면은 "기록 없음", "이상 없음"이라 쓰지 않음, 03 H).
 *
 * <p>나란히 보기(INSP-3b)·부위 사진·통지 상태는 그 기능을 만드는 PR에서 칸을 더한다(이미 있는 칸은 바꾸지 않는다).
 *
 * @param mySide 보는 사람의 측
 * @param authorSide 기준 기록을 쓰는 측(먼저 시작한 측)
 * @param late 입주 후 등록(06 INSP-2a). 입주일보다 뒤에 시작한 입주 점검이면 참이고, 화면은 시작 날짜({@code createdAt})와 함께 "입주
 *     당시 상태와 다를 수 있어요"를 붙인다. 퇴거 점검은 늘 거짓이다
 * @param lockedAt 잠긴 시각. 잠기지 않았으면 null
 * @param lockReason 잠긴 이유. 잠기지 않았으면 null
 * @param asOf 공유를 그만둬 보이는 범위를 고정한 시각(03 6-6 9번). 이 시각 뒤 상대가 더한 항목은 빠진다. 고정되지 않았으면 null
 * @param items 보이는 항목(더한 순서). 작성 중이라 보는 사람에게 아직 보이지 않으면 null
 * @param counterpartCanRequestAt 기준 측이 아닌 사람도 "지금까지 쓴 것으로" 확인 요청을 보낼 수 있게 되는 시각(시작 + {@code
 *     inspection.draft.stale-after}, 06 INSP-1f). 화면의 "작성 중이에요 · 8일째"에 쓴다
 * @param availableActions 지금 이 사람이 누를 수 있는 행동. 이름은 API 끝 단어와 같다(06 4-0 "할 수 있는 행동")
 */
public record InspectionDetail(
    UUID id,
    UUID roomRecordId,
    InspectionKind kind,
    InspectionStatus status,
    PartySide mySide,
    PartySide authorSide,
    boolean late,
    Instant lockedAt,
    LockReason lockReason,
    Instant createdAt,
    Instant asOf,
    List<Item> items,
    Instant counterpartCanRequestAt,
    List<String> availableActions) {

  /**
   * 점검 항목 하나.
   *
   * @param status 항목 응답(확인 기다림 / 맞아요 / 보류 / 이견 / 확인 없음)
   * @param addedBy 더한 측. 답하는 쪽은 반대 측이다
   * @param addedAfterLock "잠긴 뒤 추가(날짜)"(INSP-1e)
   * @param addedAfterEnd "거주 종료 뒤 추가(날짜)"(INSP-3a)
   * @param createdAt 더한 시각(위 두 표시의 날짜)
   * @param version 지금 판
   * @param photos 항목 사진({@code sortOrder} 순). 보는 사람이 볼 수 있는 것만이고 지운 사진은 흔적으로 남는다
   * @param nextRerequestAt 다음에 보충할 수 있는 시각. 보충한 적이 없으면 null(06 4-0 "다음 가능 시각")
   * @param availableActions 이 항목에 지금 누를 수 있는 행동({@code respond}·{@code supplement})
   */
  public record Item(
      UUID id,
      AckState status,
      PartySide addedBy,
      boolean addedAfterLock,
      boolean addedAfterEnd,
      Instant createdAt,
      Version version,
      List<PhotoCard> photos,
      Instant nextRerequestAt,
      List<String> availableActions) {}

  /**
   * 항목의 판({@code inspection_item_version}).
   *
   * @param no 판 번호. 항목에 답할 때 {@code seenVersionNo}로 보낸다
   * @param partName 부위 이름(지금 이름. 판에는 부위 ID만 남는다)
   */
  public record Version(int no, UUID roomPartId, String partName, String description) {}
}
