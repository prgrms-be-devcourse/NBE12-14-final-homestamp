package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.PhotoCard;
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
 * @param solo 혼자 진행 가능으로 바뀐 수리 건인지(ROOM-7)
 * @param createdAt 등록한 시각
 * @param asOf 공유를 그만둬 보이는 범위를 고정한 시각(화면의 "○월 ○일 기준", 03 6-6 9번). 이때 {@code status}·{@code
 *     content}·{@code defect}·{@code money}는 그 시각까지의 행동과 보는 사람 자신의 행동으로 다시 계산한 값이다. 고정되지 않았으면 null
 * @param content 보이는 판(고정되지 않았으면 지금 판)
 * @param defect 하자 인정
 * @param money 수리비 부담
 * @param photos 수리 건에 붙은 사진(하자 등록·보충, {@code sortOrder} 순). 보는 사람이 볼 수 있는 것만이고 지운 사진은 흔적({@code
 *     erased})으로 남는다(03 3-1, 불변식 16). 주소는 {@code POST /api/photos/view-urls}로 따로 받는다
 * @param availableActions 지금 이 사람이 누를 수 있는 행동. 이름은 API 끝 단어와 같다(06 4-0 "할 수 있는 행동")
 */
public record RepairCaseDetail(
    UUID id,
    UUID roomRecordId,
    PartySide mySide,
    PartySide registeredBy,
    RepairStatus status,
    boolean solo,
    Instant createdAt,
    Instant asOf,
    Content content,
    Defect defect,
    Money money,
    List<PhotoCard> photos,
    List<String> availableActions) {

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
