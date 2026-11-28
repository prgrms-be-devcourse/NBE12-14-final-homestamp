package com.chilsuitjo.back.settlement.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 정산 규칙(03 I·1-4 "정산 끝내기", 06 SET-1·SET-5)을 순수 함수로 본다. */
class SettlementTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
  private static final PartySide T = PartySide.TENANT;
  private static final PartySide L = PartySide.LANDLORD;

  private final SettlementTransitions rules = new SettlementTransitions(Duration.ofDays(7));

  // ---- 열기(SET-1) ----

  @Test
  void 처음_상태는_열리지_않은_정산이다() {
    assertThat(rules.initial()).isEqualTo(SettlementState.NONE);
    assertThat(rules.initial().opened()).isFalse();
  }

  @Test
  void 열지_않은_정산을_열면_열린_정산이다() {
    assertThat(rules.apply(SettlementState.NONE, entry(ActionType.OPEN_SETTLEMENT, T)))
        .isEqualTo(SettlementState.OPENED);
  }

  @Test
  void 이미_열린_정산을_다시_열면_SETTLEMENT_EXISTS() {
    assertCode(
        () -> rules.apply(SettlementState.OPENED, entry(ActionType.OPEN_SETTLEMENT, L)),
        ErrorCode.SETTLEMENT_EXISTS);
  }

  @Test
  void 정산이_아닌_행동은_INVALID_STATE() {
    assertCode(
        () -> rules.apply(SettlementState.OPENED, entry(ActionType.REGISTER, T)),
        ErrorCode.INVALID_STATE);
    assertThat(SettlementTransitions.CHANGES)
        .contains(ActionType.OPEN_SETTLEMENT, ActionType.CLOSE_FINISH_REQUEST);
  }

  // ---- 끝내기 제안(SET-5a·5c) ----

  @Test
  void 거주_종료_뒤_연결한_상대에게만_제안한다() {
    SettlementState proposed = rules.apply(open(facts()), entry(ActionType.PROPOSE_FINISH, T));
    assertThat(proposed.finishRequestedBy()).isEqualTo(T);

    assertThat(rules.proposeBlocked(open(facts(false, true, false, null, null)), T, NOW))
        .isEqualTo(ErrorCode.INVALID_STATE); // 거주 중
    assertThat(rules.proposeBlocked(open(facts(true, false, false, null, null)), T, NOW))
        .isEqualTo(ErrorCode.INVALID_STATE); // 상대 없음·연결 전
    assertThat(rules.proposeBlocked(open(facts(true, true, true, null, null)), T, NOW))
        .isEqualTo(ErrorCode.SHARING_STOPPED);
    assertThat(rules.proposeBlocked(proposed, L, NOW)).isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(rules.proposeBlocked(SettlementState.NONE, T, NOW))
        .isEqualTo(ErrorCode.INVALID_STATE);
    // 재계산(사실 모름)이면 기록된 행동을 그때 유효했던 것으로 본다
    assertThat(rules.proposeBlocked(SettlementState.OPENED, T, NOW)).isNull();
  }

  @Test
  void SET_5c_아직_안_끝났어요_뒤_7일_안에는_다시_제안할_수_없다() {
    SettlementState answered = open(facts(true, true, false, NOW.minus(Duration.ofDays(6)), null));
    assertCode(
        () -> rules.apply(answered, entry(ActionType.PROPOSE_FINISH, T)), ErrorCode.ASKED_RECENTLY);

    SettlementState later = open(facts(true, true, false, NOW.minus(Duration.ofDays(7)), null));
    assertThat(rules.proposeBlocked(later, T, NOW)).isNull();
  }

  // ---- 확인·아직 안 끝났어요(SET-5a·5c·5e) ----

  @Test
  void 상대가_본_모습으로_확인하면_정산_완료다() {
    SettlementState waiting = requested(T, facts());

    SettlementState settled = rules.apply(waiting, seen(ActionType.CONFIRM_FINISH, L, 3));

    assertThat(settled.settled()).isTrue();
    assertThat(settled.finishRequestedBy()).isNull();
    assertThat(SettlementTransitions.canAnswer(waiting, L)).isTrue();
    assertThat(SettlementTransitions.canAnswer(waiting, T)).isFalse();
    assertThat(SettlementTransitions.canAnswer(settled, L)).isFalse();
  }

  @Test
  void SET_5e_본_모습이_다르면_VERSION_CONFLICT이고_요청이_없거나_요청한_측이면_막힌다() {
    assertCode(
        () -> rules.apply(requested(T, facts()), seen(ActionType.CONFIRM_FINISH, L, 2)),
        ErrorCode.VERSION_CONFLICT);
    assertCode(
        () -> rules.apply(requested(T, facts()), seen(ActionType.CONFIRM_FINISH, L, null)),
        ErrorCode.VERSION_CONFLICT);
    assertCode(
        () -> rules.apply(open(facts()), seen(ActionType.CONFIRM_FINISH, L, 3)),
        ErrorCode.INVALID_STATE);
    assertCode(
        () -> rules.apply(requested(T, facts()), seen(ActionType.CONFIRM_FINISH, T, 3)),
        ErrorCode.NOT_COUNTERPART_SIDE);
  }

  @Test
  void 아직_안_끝났어요는_사유가_필요하고_요청을_닫는다() {
    SettlementState waiting = requested(T, facts());

    assertCode(
        () -> rules.apply(waiting, reason(ActionType.NOT_YET_FINISH, L, " ")),
        ErrorCode.REASON_REQUIRED);
    SettlementState closed = rules.apply(waiting, reason(ActionType.NOT_YET_FINISH, L, "남음"));

    assertThat(closed.finishRequestedBy()).isNull();
    assertThat(closed.settled()).isFalse();
  }

  @Test
  void 정산이_바뀌어_닫히면_열린_요청이_없어진다() {
    SettlementState closed =
        rules.apply(requested(T, facts()), entry(ActionType.CLOSE_FINISH_REQUEST, null));

    assertThat(closed.finishRequestedBy()).isNull();
  }

  // ---- 혼자 끝내기(SET-5d) ----

  @Test
  void SET_5d_혼자_진행_이유가_있으면_혼자_끝낸다() {
    SettlementState unconfirmed =
        requested(T, facts(true, true, false, null, SoloReason.UNCONFIRMED));

    assertThat(rules.apply(unconfirmed, entry(ActionType.FINISH_ALONE, T)).settled()).isTrue();
  }

  @Test
  void 혼자_진행_이유가_없거나_상대_요청이_기다리면_SOLO_NOT_ALLOWED() {
    assertThat(SettlementTransitions.aloneBlocked(requested(T, facts()), T))
        .isEqualTo(ErrorCode.SOLO_NOT_ALLOWED);
    assertThat(
            SettlementTransitions.aloneBlocked(
                requested(L, facts(true, true, false, null, SoloReason.UNCONFIRMED)), T))
        .isEqualTo(ErrorCode.SOLO_NOT_ALLOWED);
    assertThat(
            SettlementTransitions.aloneBlocked(
                open(facts(true, true, true, null, SoloReason.NO_COUNTERPART)), T))
        .isEqualTo(ErrorCode.SHARING_STOPPED);
    assertThat(
            SettlementTransitions.aloneBlocked(
                open(facts(false, true, false, null, SoloReason.NO_COUNTERPART)), T))
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(SettlementTransitions.aloneBlocked(SettlementState.NONE, T))
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(SettlementTransitions.aloneBlocked(SettlementState.OPENED, T)).isNull();
    assertCode(
        () -> rules.apply(requested(T, facts()), entry(ActionType.FINISH_ALONE, T)),
        ErrorCode.SOLO_NOT_ALLOWED);
  }

  // ---- 도우미 ----

  /** 거주 종료 뒤, 정산 모습 3번, 상대 연결됨, 공유 중단 없음, [아직 안 끝났어요] 없음, 혼자 진행 이유 없음. */
  private static SettlementState.Facts facts() {
    return facts(true, true, false, null, null);
  }

  private static SettlementState.Facts facts(
      boolean ended,
      boolean connected,
      boolean sharingStopped,
      Instant lastNotYetAt,
      SoloReason solo) {
    return new SettlementState.Facts(ended, 3, connected, sharingStopped, lastNotYetAt, solo);
  }

  private static SettlementState open(SettlementState.Facts facts) {
    return new SettlementState(true, null, false, facts);
  }

  private static SettlementState requested(PartySide by, SettlementState.Facts facts) {
    return new SettlementState(true, by, false, facts);
  }

  private static ActionEntry entry(ActionType type, PartySide side) {
    return entry(type, side, null, null);
  }

  private static ActionEntry seen(ActionType type, PartySide side, Integer seen) {
    return entry(type, side, seen, null);
  }

  private static ActionEntry reason(ActionType type, PartySide side, String reason) {
    return entry(type, side, null, reason);
  }

  private static ActionEntry entry(ActionType type, PartySide side, Integer seen, String reason) {
    return new ActionEntry(
        null,
        UUID.randomUUID(),
        0,
        type,
        side,
        "k",
        null,
        null,
        null,
        seen,
        null,
        reason,
        Map.of(),
        NOW);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }
}
