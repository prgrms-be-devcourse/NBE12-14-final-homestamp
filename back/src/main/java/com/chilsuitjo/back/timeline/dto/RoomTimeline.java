package com.chilsuitjo.back.timeline.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 호실 경과({@code GET /api/rooms/{id}/timeline}, 06 REP-11·S-06). 보는 사람이 볼 수 있는 행동을 서버 시각 순으로
 * 준다(REP-11a). 고친 내용 행동은 원래 기록과 같은 {@code target}으로 나와 화면이 그 기록 옆에 이력으로 보인다.
 *
 * @param mySide 보는 사람의 측
 * @param asOf 공유를 그만둔 뒤면 그 시각("○월 ○일 기준", 03 6-6 9번). 그만두지 않았거나 상대가 탈퇴해 제한이 풀렸으면 null(수리 건 상세의
 *     {@code asOf}와 같은 기준)
 * @param items 행동. 시각 순, 같은 시각이면 남긴 순
 */
public record RoomTimeline(PartySide mySide, Instant asOf, List<Item> items) {

  /**
   * 행동 한 줄. 행동 번호·해시·내용 지문·회원 식별값·멱등 키는 넣지 않는다(03 3-1).
   *
   * @param id 행동 ID
   * @param at 서버 시각
   * @param type 행동 종류(05 2-3)
   * @param side 남긴 측. 서버·운영 행동이면 null
   * @param target 대상. 호실 기록 자체가 대상이면 null
   * @param reason 사유
   * @param payload 행동 내용. 사진 지문은 {@code photos}로 옮겨 여기서 뺀다
   * @param photos 이 행동으로 붙인 사진. 표시 순서대로
   */
  public record Item(
      UUID id,
      Instant at,
      ActionType type,
      PartySide side,
      Target target,
      String reason,
      Map<String, Object> payload,
      List<Photo> photos) {}

  /** 행동의 대상. */
  public record Target(TargetKind kind, UUID id) {}

  /**
   * 행동에 붙은 사진. 화면은 {@code POST /api/photos/view-urls}로 주소를 받는다.
   *
   * @param erased 지운 사진이면 흔적(불변식 16), 아니면 null. 지운 사진은 주소를 받지 않고 정해진 표시로 그린다
   */
  public record Photo(UUID photoId, Erased erased) {}

  /**
   * 지운 사진의 흔적.
   *
   * @param side 지운 측. 운영이 지웠으면 null
   * @param at 지운 시각
   */
  public record Erased(PartySide side, Instant at) {}
}
