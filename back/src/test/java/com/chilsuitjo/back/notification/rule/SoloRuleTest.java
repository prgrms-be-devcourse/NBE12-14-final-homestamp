package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 요청 없이 바로 혼자 진행(03 1-1, 1-4, 06 REP-5e, #113 Q5). */
class SoloRuleTest {

  private static final Instant STOPPED = Instant.parse("2026-11-20T03:00:00Z");

  @ParameterizedTest
  @EnumSource(
      value = PartyPresence.class,
      names = {"EMPTY", "NUMBER_ONLY"})
  void 상대가_없으면_성립_시각_없이_바로_혼자_진행한다(PartyPresence presence) {
    SoloCheck check = SoloRule.direct(parties(seat(presence, null)), RequestKind.CLOSE_CONFIRM);

    assertThat(check).isEqualTo(SoloCheck.allowedBecause(SoloReason.NO_COUNTERPART));
    assertThat(check.since()).isNull();
  }

  @Test
  void 연결_전_상대는_상대_없음이_아니라_바로_열지_않는다() {
    // 링크로 볼 수 있는 기록은 72시간 뒤 "아직 연결하지 않았어요"(03 1-1 ⑥). 그 요청 이력 없이는 열지 않는다
    SoloCheck check =
        SoloRule.direct(parties(seat(PartyPresence.INVITED, null)), RequestKind.CLOSE_CONFIRM);

    assertThat(check).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 상대가_공유를_그만뒀으면_그만둔_시각부터_혼자_진행한다() {
    SoloCheck check =
        SoloRule.direct(parties(seat(PartyPresence.CONNECTED, STOPPED)), RequestKind.CLOSE_CONFIRM);

    assertThat(check).isEqualTo(SoloCheck.allowedSince(SoloReason.SHARING_STOPPED, STOPPED));
    assertThat(check.since()).isEqualTo(STOPPED);
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestKind.class,
      names = {"DEFECT_CONFIRM", "CLOSE_CONFIRM", "INSPECTION_CONFIRM"})
  void 공유_중단은_하자_종료_점검_확인에_쓴다(RequestKind kind) {
    assertThat(SoloRule.direct(parties(seat(PartyPresence.CONNECTED, STOPPED)), kind))
        .isEqualTo(SoloCheck.allowedSince(SoloReason.SHARING_STOPPED, STOPPED));
  }

  @Test
  void 그만둔_시각이_없거나_다시_공유했으면_공유_중단으로_열지_않는다() {
    // 다시 공유하면 sharing_stopped_at이 비워진다(05 party). 시각 없는 공유 중단은 이유가 아니다
    SoloCheck check =
        SoloRule.direct(parties(seat(PartyPresence.CONNECTED, null)), RequestKind.CLOSE_CONFIRM);

    assertThat(check).isEqualTo(SoloCheck.notYet(null));
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestKind.class,
      names = {"SETTLE_FINISH", "END_REQUEST"})
  void 공유_중단은_정산_끝내기와_거주_종료_요청에는_쓰지_않는다(RequestKind kind) {
    SoloCheck check = SoloRule.direct(parties(seat(PartyPresence.CONNECTED, STOPPED)), kind);

    assertThat(check).isEqualTo(SoloCheck.notYet(null));
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestKind.class,
      names = {"SETTLE_FINISH", "END_REQUEST", "DEFECT_CONFIRM", "INSPECTION_CONFIRM"})
  void 상대_없음은_혼자_진행이_열리는_종류_모두에_쓴다(RequestKind kind) {
    assertThat(SoloRule.direct(parties(PartyFacts.empty()), kind))
        .isEqualTo(SoloCheck.allowedBecause(SoloReason.NO_COUNTERPART));
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestKind.class,
      names = {
        "SITUATION_CHECK",
        "CORRECTION_CONFIRM",
        "AMOUNT_CONFIRM",
        "PROPOSAL",
        "RECEIPT_CONFIRM",
        "PROGRESS_CHECK"
      })
  void 혼자_진행이_열리지_않는_종류는_상대가_없어도_열지_않는다(RequestKind kind) {
    // decisions "늘 지키는 원칙" 4: 혼자 진행은 하자·종료·점검·정산 끝내기·거주 종료만
    assertThat(SoloRule.direct(parties(PartyFacts.empty()), kind))
        .isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 탈퇴는_접근_차단이_생기기_전까지_열지_않는다() {
    // 탈퇴 뒤 토큰 폐기·링크 무효(03 탈퇴)를 만드는 KEEP-4가 아직 없다
    SoloCheck check =
        SoloRule.direct(parties(seat(PartyPresence.WITHDRAWN, null)), RequestKind.CLOSE_CONFIRM);

    assertThat(check).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 탈퇴한_상대의_남은_공유_중단_시각으로_열지_않는다() {
    SoloCheck check =
        SoloRule.direct(parties(seat(PartyPresence.WITHDRAWN, STOPPED)), RequestKind.CLOSE_CONFIRM);

    assertThat(check).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 초대를_거절한_상대는_열지_않는다() {
    SoloCheck check =
        SoloRule.direct(parties(seat(PartyPresence.REJECTED, null)), RequestKind.CLOSE_CONFIRM);

    assertThat(check).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 수신_거부와_문자_끔으로는_열지_않는다() {
    // optedOut 출처가 정해지지 않았고(#100), 문자 끔은 수신 거부가 아니다(03 K)
    PartyFacts optedOutNoSms =
        new PartyFacts(
            UUID.randomUUID(), UUID.randomUUID(), PartyPresence.CONNECTED, null, false, true, null);

    assertThat(SoloRule.direct(parties(optedOutNoSms), RequestKind.CLOSE_CONFIRM))
        .isEqualTo(SoloCheck.notYet(null));
  }

  @ParameterizedTest
  @EnumSource(
      value = SoloReason.class,
      names = {"NO_COUNTERPART", "COUNTERPART_WITHDRAWN"},
      mode = EnumSource.Mode.EXCLUDE)
  void 성립_시각_없이_돌려줄_수_없는_이유는_계약_위반이다(SoloReason reason) {
    assertThatThrownBy(() -> SoloRule.requireDirectContract(SoloCheck.allowedBecause(reason)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(SoloRule.requireDirectContract(SoloCheck.allowedSince(reason, STOPPED)).since())
        .isEqualTo(STOPPED);
  }

  @ParameterizedTest
  @EnumSource(
      value = SoloReason.class,
      names = {"NO_COUNTERPART", "COUNTERPART_WITHDRAWN"})
  void 상대가_행동할_수_없는_이유는_성립_시각이_없어도_된다(SoloReason reason) {
    assertThat(SoloRule.requireDirectContract(SoloCheck.allowedBecause(reason)).allowed()).isTrue();
  }

  @Test
  void 불가_결과에는_이유와_성립_시각을_담을_수_없다() {
    assertThatThrownBy(() -> new SoloCheck(false, SoloReason.SHARING_STOPPED, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SoloCheck(false, null, null, STOPPED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SoloCheck(true, null, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static PartyFacts seat(PartyPresence presence, Instant sharingStoppedAt) {
    UUID memberId =
        presence == PartyPresence.CONNECTED || presence == PartyPresence.WITHDRAWN
            ? UUID.randomUUID()
            : null;
    return new PartyFacts(
        UUID.randomUUID(), memberId, presence, sharingStoppedAt, false, false, null);
  }

  private static RoomParties parties(PartyFacts counterpart) {
    PartyFacts mine =
        new PartyFacts(
            UUID.randomUUID(), UUID.randomUUID(), PartyPresence.CONNECTED, null, true, false, null);
    return new RoomParties(RoomPhase.LIVING, mine, counterpart);
  }
}
