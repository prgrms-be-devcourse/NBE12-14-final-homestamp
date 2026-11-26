package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.Map;
import java.util.UUID;

/**
 * 남은 항목 모두 확인({@code CONFIRM_ALL}, 06 4-7 {@code confirm-all})을 판단할 때의 점검 상태. 행동하는 사람 기준으로 읽은 값이다.
 *
 * @param status 점검 상태
 * @param author 점검의 기준 측
 * @param unanswered 이 사람이 답할 미응답 항목(확인 기다림·확인 없음)의 ID와 지금 판 번호. 이 사람에게 보이는 항목만
 * @param totalItems 점검의 전체 항목 수(보이지 않는 항목·답한 항목 포함)
 * @param draftHidden 기준 측의 작성 중 항목이 이 사람에게 가려져 있나(06 INSP-1a, 불변식 18). 그때는 "하자 기록 없음"을 확인할 수 없다
 * @param confirmedNoItems 이 사람이 이미 "하자 기록 없음"을 확인했나(항목 없는 점검에 앞서 남긴 {@code CONFIRM_ALL})
 */
public record ConfirmAllState(
    InspectionStatus status,
    PartySide author,
    Map<UUID, Integer> unanswered,
    int totalItems,
    boolean draftHidden,
    boolean confirmedNoItems) {

  public ConfirmAllState {
    unanswered = Map.copyOf(unanswered);
  }

  /**
   * 항목이 하나도 없는 점검의 확인("하자 기록 없음")인가. 보이는 항목이 아니라 전체 항목으로 본다: 보이지 않는 항목이 있는데 "하자 기록 없음"을 확인하면 안
   * 된다(#272 리뷰).
   */
  public boolean noItems() {
    return totalItems == 0;
  }
}
