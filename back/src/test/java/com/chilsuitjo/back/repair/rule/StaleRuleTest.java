package com.chilsuitjo.back.repair.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.AckState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StaleRuleTest {

  private static final Instant LAST = Instant.parse("2026-11-01T00:00:00Z");
  private final StaleRule rule = new StaleRule(Duration.ofDays(14));

  @Test
  void 기간이_된_때부터_멈춘_건이다() {
    assertThat(rule.isStale(LAST, LAST.plus(Duration.ofDays(14)))).isTrue();
    assertThat(rule.isStale(LAST, LAST.plus(Duration.ofDays(15)))).isTrue();
  }

  @Test
  void 기간이_되기_직전은_멈춘_건이_아니다() {
    assertThat(rule.isStale(LAST, LAST.plus(Duration.ofDays(14)).minusMillis(1))).isFalse();
  }

  @ParameterizedTest
  @EnumSource(AckState.class)
  void 처리_중이면_하자_인정과_상관없이_처리_중이다(AckState ack) {
    // 보충 요청됨이어도 처리 중이면 한 줄 IN_PROGRESS(#370 B-2)
    assertThat(StaleRule.reasonOf(RepairStatus.IN_PROGRESS, ack)).contains(StaleReason.IN_PROGRESS);
  }

  @Test
  void 시작_전이면서_보충_요청됨이면_보류다() {
    assertThat(StaleRule.reasonOf(RepairStatus.NOT_STARTED, AckState.NEED_MORE))
        .contains(StaleReason.NEED_MORE);
  }

  @ParameterizedTest
  @EnumSource(
      value = AckState.class,
      names = {"NEED_MORE"},
      mode = EnumSource.Mode.EXCLUDE)
  void 시작_전이면서_보충_요청됨이_아니면_멈춘_건이_아니다(AckState ack) {
    assertThat(StaleRule.reasonOf(RepairStatus.NOT_STARTED, ack)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(AckState.class)
  void 종료_확인_대기와_처리_완료는_멈춘_건이_아니다(AckState ack) {
    assertThat(StaleRule.reasonOf(RepairStatus.CLOSE_WAIT, ack)).isEmpty();
    assertThat(StaleRule.reasonOf(RepairStatus.DONE, ack)).isEmpty();
  }

  @Test
  void 기간은_0보다_커야_한다() {
    assertThatThrownBy(() -> new StaleRule(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
