package com.chilsuitjo.back.repair.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** 수리 건 규칙(03 D·G, 06 REP-1·2·9). 순수 함수라 pipeline 없이 부른다(안내서 5-1). */
class RepairTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");
  private static final Duration INTERVAL = Duration.ofDays(7);

  private final RepairTransitions rules = new RepairTransitions(INTERVAL);

  @Test
  void 처음_상태는_아직_없는_기록이다() {
    assertThat(rules.initial()).isEqualTo(RepairState.NONE);
    assertThat(rules.initial().exists()).isFalse();
  }

  @Test
  void 등록하면_시작_전이고_확인을_기다리며_부담은_미정이다() {
    RepairState next =
        rules.apply(RepairState.NONE, entry(ActionType.REGISTER, PartySide.LANDLORD));

    assertThat(next)
        .isEqualTo(
            new RepairState(
                RepairStatus.NOT_STARTED,
                AckState.PENDING,
                MoneyStatus.UNDECIDED,
                PartySide.LANDLORD,
                1,
                null,
                false,
                false));
    assertThat(next.exists()).isTrue();
  }

  @Test
  void 이미_있는_기록에는_다시_등록할_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                registered(AckState.PENDING), entry(ActionType.REGISTER, PartySide.TENANT)));
  }

  @Test
  void 측이_없는_주체는_등록할_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(RepairState.NONE, entry(ActionType.REGISTER, null)));
  }

  // ---- 하자 응답(REP-2a~d·f, REP-9a) ----

  @Test
  void REP_2a_상대가_맞아요를_누르면_하자_인정만_바뀐다() {
    RepairState next =
        rules.apply(registered(AckState.PENDING), respond(PartySide.LANDLORD, Answer.ACK, null, 1));

    assertThat(next.defectAck()).isEqualTo(AckState.ACK);
    assertThat(next.status()).isEqualTo(RepairStatus.NOT_STARTED);
    assertThat(next.moneyStatus()).isEqualTo(MoneyStatus.UNDECIDED);
  }

  @Test
  void REP_2b_내가_등록한_하자는_내가_확인할_수_없다() {
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () ->
            rules.apply(
                registered(AckState.PENDING), respond(PartySide.TENANT, Answer.ACK, null, 1)));
  }

  @ParameterizedTest
  @EnumSource(
      value = Answer.class,
      names = {"NEED_MORE", "DISPUTE"})
  void REP_2c_9a_보류와_이견에는_사유가_있어야_한다(Answer answer) {
    assertError(
        ErrorCode.REASON_REQUIRED,
        () ->
            rules.apply(
                registered(AckState.PENDING), respond(PartySide.LANDLORD, answer, null, 1)));
  }

  @Test
  void REP_2d_보류는_사유와_함께_보충_요청됨이_된다() {
    RepairState next =
        rules.apply(
            registered(AckState.PENDING),
            respond(PartySide.LANDLORD, Answer.NEED_MORE, "사진이 흐려요", 1));

    assertThat(next.defectAck()).isEqualTo(AckState.NEED_MORE);
  }

  @Test
  void REP_2f_이견은_나중에_맞아요로_바꿀_수_있고_맞아요는_바꿀_수_없다() {
    RepairState acked =
        rules.apply(registered(AckState.DISPUTE), respond(PartySide.LANDLORD, Answer.ACK, null, 1));
    assertThat(acked.defectAck()).isEqualTo(AckState.ACK);

    assertError(
        ErrorCode.ACK_FINAL,
        () -> rules.apply(acked, respond(PartySide.LANDLORD, Answer.DISPUTE, "다시 보니 달라요", 1)));
  }

  @Test
  void 보류에서는_이견으로_바로_바꾸지_않는다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                registered(AckState.NEED_MORE),
                respond(PartySide.LANDLORD, Answer.DISPUTE, "사실과 달라요", 1)));
  }

  @Test
  void 본_판_번호가_지금_판과_다르면_VERSION_CONFLICT() {
    assertError(
        ErrorCode.VERSION_CONFLICT,
        () ->
            rules.apply(
                registered(AckState.PENDING), respond(PartySide.LANDLORD, Answer.ACK, null, 2)));
    assertError(
        ErrorCode.VERSION_CONFLICT,
        () ->
            rules.apply(
                registered(AckState.PENDING), respond(PartySide.LANDLORD, Answer.ACK, null, null)));
  }

  @Test
  void 없는_수리_건에는_응답할_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(RepairState.NONE, respond(PartySide.LANDLORD, Answer.ACK, null, 1)));
  }

  @Test
  void 상대가_볼_수_없는_하자에는_상대가_답할_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                registered(AckState.PENDING, true),
                respond(PartySide.LANDLORD, Answer.ACK, null, 1)));
    // 등록한 측이 부르면 볼 수 있는지와 상관없이 상대 측이 아니라서 거절된다
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () ->
            rules.apply(
                registered(AckState.PENDING, true),
                respond(PartySide.TENANT, Answer.ACK, null, 1)));
  }

  // ---- 보충하고 다시 확인 요청(REP-2e) ----

  @ParameterizedTest
  @EnumSource(
      value = AckState.class,
      names = {"NEED_MORE", "DISPUTE"})
  void REP_2e_보류나_이견이면_등록한_쪽이_보충해_확인_기다림으로_되돌린다(AckState objection) {
    RepairState next = rules.apply(registered(objection), supplement(PartySide.TENANT, NOW));

    assertThat(next.defectAck()).isEqualTo(AckState.PENDING);
    assertThat(next.lastRerequestAt()).isEqualTo(NOW);
    assertThat(next.status()).isEqualTo(RepairStatus.NOT_STARTED);
  }

  @Test
  void 상대_측은_보충할_수_없다() {
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () -> rules.apply(registered(AckState.NEED_MORE), supplement(PartySide.LANDLORD, NOW)));
  }

  @ParameterizedTest
  @EnumSource(
      value = AckState.class,
      names = {"PENDING", "ACK"})
  void 보류나_이견이_아니면_보충할_수_없다(AckState state) {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(registered(state), supplement(PartySide.TENANT, NOW)));
  }

  @Test
  void 하자_확인_요청을_열_수_없는_단계면_보충할_수_없다() {
    RepairState closed =
        new RepairState(
            RepairStatus.NOT_STARTED,
            AckState.NEED_MORE,
            MoneyStatus.UNDECIDED,
            PartySide.TENANT,
            1,
            null,
            false,
            false);

    assertError(
        ErrorCode.INVALID_STATE, () -> rules.apply(closed, supplement(PartySide.TENANT, NOW)));
  }

  @Test
  void REP_2e_보충은_7일에_한_번이고_7일이_지나면_다시_된다() {
    RepairState supplementedBefore =
        registered(AckState.NEED_MORE).withLastRerequestAt(NOW.minus(INTERVAL).plusSeconds(1));
    assertError(
        ErrorCode.ASKED_RECENTLY,
        () -> rules.apply(supplementedBefore, supplement(PartySide.TENANT, NOW)));

    RepairState weekAgo = registered(AckState.NEED_MORE).withLastRerequestAt(NOW.minus(INTERVAL));
    assertThat(rules.apply(weekAgo, supplement(PartySide.TENANT, NOW)).defectAck())
        .isEqualTo(AckState.PENDING);
  }

  // ---- 아직 없는 행동 ----

  /** 아직 만들지 않은 수리 행동. 새 행동을 붙이면 여기서 빼고 따로 테스트한다. */
  static Stream<ActionType> notYetRepairActions() {
    Set<ActionType> done =
        Set.of(ActionType.REGISTER, ActionType.RESPOND_DEFECT, ActionType.SUPPLEMENT);
    return Arrays.stream(ActionType.values())
        .filter(type -> type.area() == ActionType.Area.REPAIR && !done.contains(type));
  }

  @ParameterizedTest
  @MethodSource("notYetRepairActions")
  void 아직_만들지_않은_수리_행동은_INVALID_STATE(ActionType type) {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(registered(AckState.PENDING), entry(type, PartySide.LANDLORD)));
  }

  @Test
  void 수리가_아닌_행동은_INVALID_STATE() {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(RepairState.NONE, entry(ActionType.HIDE_PART, PartySide.TENANT)));
  }

  /** 세입자가 등록한 판 #1 수리 건. 하자 확인 요청은 열 수 있고 상대에게 보인다. */
  private static RepairState registered(AckState defectAck) {
    return registered(defectAck, false);
  }

  private static RepairState registered(AckState defectAck, boolean hiddenFromCounterpart) {
    return new RepairState(
        RepairStatus.NOT_STARTED,
        defectAck,
        MoneyStatus.UNDECIDED,
        PartySide.TENANT,
        1,
        null,
        true,
        hiddenFromCounterpart);
  }

  private static void assertError(ErrorCode code, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  private static ActionEntry respond(PartySide side, Answer answer, String reason, Integer seen) {
    return entry(
        ActionType.RESPOND_DEFECT, side, seen, reason, Map.of("answer", answer.name()), NOW);
  }

  private static ActionEntry supplement(PartySide side, Instant at) {
    return entry(ActionType.SUPPLEMENT, side, null, null, Map.of(), at);
  }

  private static ActionEntry entry(ActionType type, PartySide side) {
    return entry(type, side, null, null, Map.of(), NOW);
  }

  private static ActionEntry entry(
      ActionType type,
      PartySide side,
      Integer seenVersionNo,
      String reason,
      Map<String, Object> payload,
      Instant at) {
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
        seenVersionNo,
        null,
        reason,
        payload,
        at);
  }
}
