package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 03 K "거주 종료 뒤": 호실 단계마다 열 수 있는 확인 요청. */
class RequestOpeningTest {

  @Test
  void 거주_중에는_모든_종류를_연다() {
    assertThat(Arrays.stream(RequestKind.values()))
        .allMatch(kind -> RequestOpening.allowed(RoomPhase.LIVING, kind));
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestKind.class,
      names = {"INSPECTION_CONFIRM", "CORRECTION_CONFIRM", "SETTLE_FINISH", "RECEIPT_CONFIRM"})
  void 거주_종료_뒤에도_정산을_마무리하는_확인은_연다(RequestKind kind) {
    assertThat(RequestOpening.allowed(RoomPhase.ENDED, kind)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestKind.class,
      names = {"DEFECT_CONFIRM", "PROGRESS_CHECK", "SITUATION_CHECK", "END_REQUEST"})
  void 거주_종료_뒤에는_거주_중_확인을_열지_않는다(RequestKind kind) {
    assertThat(RequestOpening.allowed(RoomPhase.ENDED, kind)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestKind.class,
      names = {"PROPOSAL", "AMOUNT_CONFIRM"})
  void 제안_거주_종료_뒤에도_돈_안건과_지불_금액_확인은_연다(RequestKind kind) {
    assertThat(RequestOpening.allowed(RoomPhase.ENDED, kind)).isTrue();
  }

  @Test
  void 제안_거주_종료_뒤에는_수리_종료_확인을_열지_않는다() {
    assertThat(RequestOpening.allowed(RoomPhase.ENDED, RequestKind.CLOSE_CONFIRM)).isFalse();
  }

  @Test
  void 정산_완료_뒤에는_아무것도_열지_않는다() {
    assertThat(Arrays.stream(RequestKind.values()))
        .noneMatch(kind -> RequestOpening.allowed(RoomPhase.SETTLED, kind));
  }
}
