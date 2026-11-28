package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 점검이 지금 잠기나(03 H "잠기는 조건", 06 INSP-1b·1c·1d, 불변식 21). 순수 함수다. 항목 상태만 받아 판단하므로 항목 응답·모두 확인 뒤에도, 나중에
 * 상대 미확인이 붙는 경로(기다림 타이머, #326 선행 의존성)에서도 같은 함수를 부른다.
 *
 * <p>잠기는 조건: 확인 요청 상태이고 <b>모든 항목이 확인·보류·이견·상대 미확인 중 하나</b>(확인 기다림 항목이 하나도 없음). 상대 미확인도 잠금 조건을 채운다.
 * 방향별 점검 확인 요청이 "답함"으로 닫히는 조건(그 방향에 확인 기다림·상대 미확인이 없음, #235)과 다르므로 요청이 닫혔는지가 아니라 항목 상태로 본다.
 *
 * <p>기준 측 항목이 없으면(항목이 하나도 없거나 상대가 더한 항목만 있음) 기준 측이 아닌 쪽의 "하자 기록 없음" 확인이 기준 측 방향의 답이다(03 H "항목이 하나도
 * 없어도 … 상대가 확인해야 잠긴다", #392 논의 6). 항목이 하나도 없으면 {@link LockReason#NO_ITEMS}, 상대가 더한 항목이 있으면 {@link
 * LockReason#ALL_RESOLVED}다.
 *
 * <p>혼자 잠금(상대 없음·탈퇴 등)과 기준 측 방향의 72시간 미확인으로 잠기는 경로는 #392에서 정하는 중이라 여기서 보지 않는다. 이 함수는 응답 사실만 보고 혼자
 * 진행 이유는 보지 않는다.
 */
public final class InspectionLock {

  /** {@code LOCK} 행동 payload의 잠긴 이유 키. 값은 {@link LockReason} 이름이다. */
  public static final String LOCK_REASON = "lockReason";

  private InspectionLock() {}

  /**
   * 지금 잠기면 그 이유, 아니면 빈 값.
   *
   * @param status 점검 상태. 확인 요청일 때만 잠긴다(작성 중이면 아직, 잠겼으면 다시 잠그지 않음)
   * @param author 기준 측
   * @param items 이 점검의 모든 항목(보는 사람과 상관없이)
   * @param noItemsConfirmed 기준 측이 아닌 쪽이 "하자 기록 없음"을 확인했나({@code CONFIRM_ALL}, 03 H). 기준 측 항목이 없을 때만
   *     판단에 쓴다
   */
  public static Optional<LockReason> decide(
      InspectionStatus status, PartySide author, List<Item> items, boolean noItemsConfirmed) {
    Objects.requireNonNull(author, "author");
    Objects.requireNonNull(items, "items");
    if (status != InspectionStatus.REQUESTED) {
      return Optional.empty();
    }
    if (items.isEmpty()) {
      return noItemsConfirmed ? Optional.of(LockReason.NO_ITEMS) : Optional.empty();
    }
    if (items.stream().anyMatch(item -> item.status() == AckState.PENDING)) {
      return Optional.empty();
    }
    if (items.stream().noneMatch(item -> item.addedBy() == author) && !noItemsConfirmed) {
      // 기준 측 항목이 없으면 "하자 기록 없음" 확인이 기준 측 방향의 답이다(#392 논의 6)
      return Optional.empty();
    }
    return Optional.of(LockReason.ALL_RESOLVED);
  }

  /**
   * 잠금 판단에 쓰는 항목 사실.
   *
   * @param addedBy 항목을 더한 측
   * @param status 항목 응답(확인 기다림 / 맞아요 / 보류 / 이견 / 상대 미확인)
   */
  public record Item(PartySide addedBy, AckState status) {

    public Item {
      Objects.requireNonNull(addedBy, "addedBy");
      Objects.requireNonNull(status, "status");
    }
  }
}
