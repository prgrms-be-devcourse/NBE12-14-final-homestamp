package com.chilsuitjo.back.inspection.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.repair.dto.RepairCaseSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 점검({@code GET /api/inspections/{id}}, 06 4-7·S-14).
 *
 * <p>작성 중인 점검을 기준 측이 아닌 사람이 보면 기준 측이 쓴 항목은 보이지 않고 "작성 중"만 보인다(06 INSP-1a): {@code items}에는 그 사람이 직접
 * 더한 항목만 있다(#286). 화면은 {@code authorSide}로 "○○이 기준 기록을 쓰고 있어요"를 보여 준다. 적지 않은 부위는 항목이 없다(화면은 "기록
 * 없음", "이상 없음"이라 쓰지 않음, 03 H).
 *
 * <p>부위 사진·통지 상태는 그 기능을 만드는 PR에서 칸을 더한다(이미 있는 칸은 바꾸지 않는다).
 *
 * @param mySide 보는 사람의 측
 * @param authorSide 기준 기록을 쓰는 측(먼저 시작한 측)
 * @param late 입주 후 등록(06 INSP-2a). 입주일보다 뒤에 시작한 입주 점검이면 참이고, 화면은 시작 날짜({@code createdAt})와 함께 "입주
 *     당시 상태와 다를 수 있어요"를 붙인다. 퇴거 점검은 늘 거짓이다
 * @param lockedAt 잠긴 시각. 잠기지 않았으면 null
 * @param lockReason 잠긴 이유. 잠기지 않았으면 null
 * @param asOf 공유를 그만둬 보이는 범위를 고정한 시각(03 6-6 9번). 이 시각 뒤 상대가 더한 항목은 빠진다. 고정되지 않았으면 null
 * @param items 보이는 항목(더한 순서). 늘 목록이다(작성 중 기준 측이 아니면 자기가 더한 항목만)
 * @param counterpartCanRequestAt 기준 측이 아닌 사람도 "지금까지 쓴 것으로" 확인 요청을 보낼 수 있게 되는 시각(시작 + {@code
 *     inspection.draft.stale-after}, 06 INSP-1f). 화면의 "작성 중이에요 · 8일째"에 쓴다
 * @param availableActions 지금 이 사람이 누를 수 있는 행동. 이름은 API 끝 단어와 같다(06 4-0 "할 수 있는 행동")
 * @param sideBySide 퇴거 점검의 나란히 보기(06 INSP-3b). 입주 점검이면 null
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
    List<String> availableActions,
    SideBySide sideBySide) {

  /**
   * 퇴거 점검 나란히 보기(06 INSP-3b, 08 9장 "퇴거 때 나란히 보기"). 같은 부위의 입주 점검 항목 · 수리 건 · 퇴거 점검 항목을 한 줄에 모은다.
   *
   * @param moveIn 이 사람에게 보이는 입주 점검. 없거나 볼 수 없으면 null(화면은 부위마다 "입주 점검에 기록 없음")
   * @param parts 기록이 하나라도 있는 부위. 퇴거 항목의 부위 순(더한 순서)을 먼저, 그 뒤 입주 항목·수리 건에만 있는 부위를 처음 나온 순서로 둔다. 어디에도
   *     기록이 없는 부위는 없다("이상 없음"이라 쓰지 않음, 03 H)
   */
  public record SideBySide(MoveIn moveIn, List<Part> parts) {}

  /**
   * 나란히 보기의 입주 점검 머리. 입주 때의 상태 표시(확인·보충 요청·이견)는 항목마다 그대로 보인다(08 9장 [반드시]).
   *
   * @param id 입주 점검(상세는 {@code GET /api/inspections/{id}})
   * @param status 이 사람에게 보이는 입주 점검 상태(입주 점검 상세의 {@code status}와 같음)
   * @param lockedAt 잠긴 시각. 잠기지 않았으면 null
   * @param late 늦은 입주 점검(INSP-2a)
   * @param createdAt 입주 점검을 시작한 시각(화면의 "입주 3/2")
   */
  public record MoveIn(
      UUID id, InspectionStatus status, Instant lockedAt, boolean late, Instant createdAt) {}

  /**
   * 나란히 보기의 부위 한 줄.
   *
   * @param roomPartId 부위
   * @param partName 부위 이름(지금 이름)
   * @param mark 표시. 입주 점검 항목이 있으면 "입주 기존 하자"({@code PREEXISTING}), 없고 수리 건이 있으면 "거주 중 하자"({@code
   *     DURING_STAY}), 퇴거 항목만 있으면 null(판정하지 않음, 03 원칙 3: 두 기록이 있다는 사실만 보인다)
   * @param moveInItems 이 부위의 입주 점검 항목(입주 점검 상세의 {@code items}와 같은 값·같은 보이는 범위). 없으면 빈 목록
   * @param repairCases 이 부위의 수리 건(등록 순, 수리 건 상세와 같은 보이는 범위·판). 없으면 빈 목록
   * @param moveOutItemIds 이 부위의 퇴거 점검 항목 ID({@link InspectionDetail#items}에 있는 것). 없으면 빈 목록
   */
  public record Part(
      UUID roomPartId,
      String partName,
      SideMark mark,
      List<Item> moveInItems,
      List<RepairCaseSummary> repairCases,
      List<UUID> moveOutItemIds) {}

  /** 나란히 보기의 부위 표시(06 5-2 S-14 "퇴거" 줄 "입주 기존 하자 / 거주 중 하자"). */
  public enum SideMark {
    PREEXISTING,
    DURING_STAY
  }

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
