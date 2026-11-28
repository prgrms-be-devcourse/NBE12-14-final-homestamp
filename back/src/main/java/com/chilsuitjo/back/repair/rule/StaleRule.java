package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.global.common.AckState;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 오래 멈춘 수리 건의 기준(06 REP-6a·6e, #370 B-2). 홈의 "오래 멈춘 건"이 쓴다. 상태 둘과 기간 하나만 보는 판단이라 DB를 보지 않는다.
 *
 * <ul>
 *   <li>처리 중({@code IN_PROGRESS})이면 하자 인정과 상관없이 {@code IN_PROGRESS}다. 보충 요청됨이어도 줄은 하나다(종료 요청은 보류에서도
 *       된다, {@link RepairTransitions})
 *   <li>시작 전({@code NOT_STARTED})이면서 하자가 보충 요청됨(보류)이면 {@code NEED_MORE}다
 *   <li>종료 확인 대기({@code CLOSE_WAIT})·처리 완료({@code DONE})는 넣지 않는다. 종료 확인 대기는 홈의 수리비 정리 쪽이다(MONEY-5a)
 * </ul>
 *
 * 기간은 마지막 당사자 행동(06 4-6 {@code lastActionAt}) + {@code repair.stale-after}(06 0-1)가 된 때부터다(이상, 다른
 * 기간과 같은 경계).
 */
@Component
public class StaleRule {

  private final Duration staleAfter;

  public StaleRule(@Value("${app.repair.stale-after:14d}") Duration staleAfter) {
    Objects.requireNonNull(staleAfter, "staleAfter");
    if (staleAfter.isNegative() || staleAfter.isZero()) {
      throw new IllegalArgumentException("repair.stale-after는 0보다 커야 해요");
    }
    this.staleAfter = staleAfter;
  }

  /** 이 상태의 건이 오래 멈춘 건이 될 수 있다면 그 이유. 될 수 없으면 비어 있다. */
  public static Optional<StaleReason> reasonOf(RepairStatus status, AckState defectAck) {
    if (status == RepairStatus.IN_PROGRESS) {
      return Optional.of(StaleReason.IN_PROGRESS);
    }
    if (status == RepairStatus.NOT_STARTED && defectAck == AckState.NEED_MORE) {
      return Optional.of(StaleReason.NEED_MORE);
    }
    return Optional.empty();
  }

  /** 마지막 당사자 행동에서 기간이 지났나(지난 때 포함). */
  public boolean isStale(Instant lastActionAt, Instant now) {
    return !now.isBefore(lastActionAt.plus(staleAfter));
  }
}
