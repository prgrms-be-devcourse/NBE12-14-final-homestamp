package com.chilsuitjo.back.inspection.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 잠금 판단(03 H "잠기는 조건", #326)을 항목 상태만으로 본다. */
class InspectionLockTest {

  private static final PartySide AUTHOR = PartySide.TENANT;
  private static final PartySide OTHER = PartySide.LANDLORD;

  @ParameterizedTest
  @EnumSource(
      value = AckState.class,
      names = {"ACK", "NEED_MORE", "DISPUTE", "UNCONFIRMED"})
  void 확인_보류_이견_상대_미확인은_모두_잠금_조건을_채운다(AckState answered) {
    assertThat(
            decide(InspectionStatus.REQUESTED, item(AUTHOR, answered), item(AUTHOR, AckState.ACK)))
        .contains(LockReason.ALL_RESOLVED);
  }

  @Test
  void 확인_기다림이_하나라도_있으면_잠기지_않는다() {
    assertThat(
            decide(
                InspectionStatus.REQUESTED,
                item(AUTHOR, AckState.ACK),
                item(OTHER, AckState.PENDING)))
        .isEmpty();
  }

  @Test
  void 두_방향_항목이_모두_정리되면_잠긴다() {
    assertThat(
            decide(
                InspectionStatus.REQUESTED,
                item(AUTHOR, AckState.UNCONFIRMED),
                item(OTHER, AckState.DISPUTE)))
        .contains(LockReason.ALL_RESOLVED);
  }

  @ParameterizedTest
  @EnumSource(
      value = InspectionStatus.class,
      names = {"DRAFT", "LOCKED"})
  void 확인_요청이_아니면_잠그지_않는다(InspectionStatus status) {
    assertThat(decide(status, item(AUTHOR, AckState.ACK))).isEmpty();
    assertThat(InspectionLock.decide(status, AUTHOR, List.of(), true)).isEmpty();
  }

  @Test
  void 항목이_없으면_하자_기록_없음_확인이_있을_때만_잠긴다() {
    assertThat(InspectionLock.decide(InspectionStatus.REQUESTED, AUTHOR, List.of(), false))
        .isEmpty();
    assertThat(InspectionLock.decide(InspectionStatus.REQUESTED, AUTHOR, List.of(), true))
        .contains(LockReason.NO_ITEMS);
  }

  @Test
  void 기준_측_항목_없이_상대_항목만_있으면_논의_전이라_잠그지_않는다() {
    assertThat(
            InspectionLock.decide(
                InspectionStatus.REQUESTED, AUTHOR, List.of(item(OTHER, AckState.ACK)), true))
        .isEmpty();
  }

  private static java.util.Optional<LockReason> decide(
      InspectionStatus status, InspectionLock.Item... items) {
    return InspectionLock.decide(status, AUTHOR, List.of(items), false);
  }

  private static InspectionLock.Item item(PartySide addedBy, AckState status) {
    return new InspectionLock.Item(addedBy, status);
  }
}
