package com.chilsuitjo.back.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.rule.ActionVisibility.Cutoff;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 지불이 보는 사람에게 보이는 범위(03 3-1, 불변식 18). 수리 건 상세의 {@code RepairSight.sees}와 같은 기준이다. */
class PaymentStateStoreTest {

  private static final Instant STOPPED = Instant.parse("2026-11-20T00:00:00Z");
  private static final Instant BEFORE = STOPPED.minusSeconds(1);
  private static final Instant AFTER = STOPPED.plusSeconds(1);

  @Test
  void 내_기록은_공유를_그만둔_뒤에도_보인다() {
    Cutoff cutoff = new Cutoff(STOPPED, null);

    assertThat(PaymentStateStore.sees(cutoff, PartySide.TENANT, PartySide.TENANT, AFTER)).isTrue();
  }

  @Test
  void 상대_기록은_공유를_그만둔_시각까지만_보인다() {
    Cutoff cutoff = new Cutoff(STOPPED, null);

    assertThat(PaymentStateStore.sees(cutoff, PartySide.TENANT, PartySide.LANDLORD, STOPPED))
        .isTrue();
    assertThat(PaymentStateStore.sees(cutoff, PartySide.TENANT, PartySide.LANDLORD, AFTER))
        .isFalse();
  }

  @Test
  void 상대가_탈퇴했으면_공유를_그만둔_동안_남긴_기록은_보이지_않는다() {
    Cutoff cutoff = new Cutoff(null, STOPPED);

    assertThat(PaymentStateStore.sees(cutoff, PartySide.TENANT, PartySide.LANDLORD, BEFORE))
        .isTrue();
    assertThat(PaymentStateStore.sees(cutoff, PartySide.TENANT, PartySide.LANDLORD, AFTER))
        .isFalse();
  }

  @Test
  void 공유를_그만두지_않았으면_다_보인다() {
    assertThat(PaymentStateStore.sees(Cutoff.NONE, PartySide.TENANT, PartySide.LANDLORD, AFTER))
        .isTrue();
  }
}
