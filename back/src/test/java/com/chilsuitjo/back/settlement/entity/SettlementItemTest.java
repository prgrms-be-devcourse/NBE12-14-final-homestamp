package com.chilsuitjo.back.settlement.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 정산 항목 팩토리가 V1 칸 규칙(출처와 수리 건의 짝, 종류 30자)을 자바에서 먼저 지키는지 본다. */
class SettlementItemTest {

  private static final UUID ROOM = UUID.randomUUID();
  private static final UUID SETTLEMENT = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

  @Test
  void 수리_건_후보에는_원래_수리_건이_필요하다() {
    assertThatThrownBy(() -> SettlementItem.forRepairCase(ROOM, SETTLEMENT, null, NOW))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("수리 건에서 온 정산 항목에는 수리 건이 있어야 해요");
  }

  @Test
  void 직접_입력한_항목은_부위를_비울_수_있다() {
    SettlementItem item = SettlementItem.forManual(ROOM, SETTLEMENT, null, "관리비", NOW);

    assertThat(item.getRoomPartId()).isNull();
    assertThat(item.getRepairCaseId()).isNull();
  }

  @Test
  void 종류가_null이면_막는다() {
    assertThatThrownBy(() -> SettlementItem.forManual(ROOM, SETTLEMENT, null, null, NOW))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("정산 항목에는 종류가 있어야 해요");
  }

  @ParameterizedTest
  @EmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void 종류가_비었으면_입력을_안내한다(String category) {
    assertThatThrownBy(() -> SettlementItem.forManual(ROOM, SETTLEMENT, null, category, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("정산 항목의 종류를 입력해 주세요");
  }

  @Test
  void 종류는_DB와_같이_30자까지_허용한다() {
    String category = "가".repeat(30);

    assertThat(SettlementItem.forManual(ROOM, SETTLEMENT, null, category, NOW).getCategory())
        .isEqualTo(category);
    assertThatThrownBy(() -> SettlementItem.forManual(ROOM, SETTLEMENT, null, category + "가", NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("정산 항목의 종류는 30자 이내로 입력해 주세요");
  }

  @Test
  void 보조_평면_문자도_DB와_같이_한_자로_센다() {
    String category = "😀".repeat(30);

    assertThat(SettlementItem.forManual(ROOM, SETTLEMENT, null, category, NOW).getCategory())
        .isEqualTo(category);
    assertThatThrownBy(() -> SettlementItem.forManual(ROOM, SETTLEMENT, null, category + "😀", NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
