package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.SoloReason;
import java.time.Instant;
import java.util.Objects;

/**
 * 혼자 진행 판단 결과.
 *
 * <p>{@code since}는 바로 혼자 진행({@code WaitService#canGoSoloDirectly})에서 03 1-4 "그 뒤로 이 건에 상대 행동이
 * 없으면"을 부르는 쪽이 확인하는 기준이다. 값이 있으면 부르는 쪽은 그 시각 뒤에 이 건에서 상대가 한 행동 중 <b>행동하는 측에게 보이는 것</b>이 있는지 본다
 * (3-1. 공유를 그만둔 상대의 나만 보는 기록으로 판단이 바뀌면 그 기록이 있다는 사실이 드러난다). 값이 없으면(null) 그 이유가 이어지는 동안 상대가 이 호실에서
 * 행동할 수 없다는 뜻이고, 그런 이유만 null을 돌려준다({@code SoloRule#requireDirectContract}). null은 허용이라는 뜻이 아니다. 허용은
 * {@code allowed}만 본다(#113 Q5).
 *
 * @param allowed 지금 혼자 진행할 수 있나
 * @param reason 할 수 있으면 그 이유(화면에 보여 줌, 03 1-1 ①~⑥), 아니면 null
 * @param opensAt 아직 못 하지만 기다리면 열리는 시각(미확인 기한). 모르거나 열리지 않으면 null
 * @param since 할 수 있으면 그 이유가 성립한 시각. 위 설명의 경우에만 null. 할 수 없으면 null
 */
public record SoloCheck(boolean allowed, SoloReason reason, Instant opensAt, Instant since) {

  public SoloCheck {
    if (allowed) {
      Objects.requireNonNull(reason, "reason");
      if (opensAt != null) {
        throw new IllegalArgumentException("이미 혼자 진행할 수 있으면 열리는 시각이 없어요");
      }
    } else if (reason != null || since != null) {
      throw new IllegalArgumentException("혼자 진행할 수 없으면 이유와 성립 시각이 없어요");
    }
  }

  /** 이유만 있고 성립 시각은 모름. 요청 기준 판단({@code canGoSolo})이나 시각이 필요 없는 이유에 쓴다. */
  public static SoloCheck allowedBecause(SoloReason reason) {
    return new SoloCheck(true, reason, null, null);
  }

  /** 이유와 그 이유가 성립한 시각. 시각 없이 돌려줄 수 있는 이유(상대 없음 등)에 시각을 붙여도 된다. 부르는 쪽은 그 시각 뒤를 확인할 뿐이다. */
  public static SoloCheck allowedSince(SoloReason reason, Instant since) {
    return new SoloCheck(true, reason, null, Objects.requireNonNull(since, "since"));
  }

  public static SoloCheck notYet(Instant opensAt) {
    return new SoloCheck(false, null, opensAt, null);
  }
}
