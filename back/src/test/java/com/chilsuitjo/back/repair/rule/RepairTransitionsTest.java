package com.chilsuitjo.back.repair.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

  private final RepairTransitions rules = new RepairTransitions(INTERVAL, INTERVAL);

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
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    assertThat(next.exists()).isTrue();
  }

  @Test
  void REP_10_등록_보류_보충_맞아요_이력을_처음부터_다시_계산할_수_있다() {
    // 행동 기록만 있고 호실 단계·공유 중단은 모른다(null). 기록된 행동은 그때 서버가 확인했으므로 다시 막지 않는다
    List<ActionEntry> history =
        List.of(
            entry(ActionType.REGISTER, PartySide.TENANT),
            respond(PartySide.LANDLORD, Answer.NEED_MORE, "사진이 흐려요", 1),
            supplement(PartySide.TENANT, NOW.plusSeconds(60)),
            respond(PartySide.LANDLORD, Answer.ACK, null, 1));

    RepairState folded = Replay.fold(rules, history);

    assertThat(folded.defectAck()).isEqualTo(AckState.ACK);
    assertThat(folded.status()).isEqualTo(RepairStatus.NOT_STARTED);
    assertThat(folded.lastRerequestAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(folded.canOpenDefectConfirm()).isNull();
    assertThat(folded.hiddenFromCounterpart()).isNull();
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
            null,
            null,
            null,
            false,
            true,
            true,
            false,
            null);

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

  // ---- 수리 진행(REP-4·5) ----

  @ParameterizedTest
  @EnumSource(AckState.class)
  void REP_4a_하자_인정과_상관없이_누구든_수리를_시작하고_하자_인정은_그대로다(AckState ack) {
    for (PartySide side : PartySide.values()) {
      RepairState next = rules.apply(registered(ack), entry(ActionType.START_REPAIR, side));

      assertThat(next.status()).isEqualTo(RepairStatus.IN_PROGRESS);
      assertThat(next.defectAck()).isEqualTo(ack);
    }
  }

  @Test
  void 시작_전이_아니면_다시_시작할_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(inProgress(), entry(ActionType.START_REPAIR, PartySide.TENANT)));
  }

  @Test
  void 종료를_요청하면_요청한_측을_남기고_종료_확인_대기가_된다() {
    RepairState next =
        rules.apply(inProgress(), entry(ActionType.REQUEST_CLOSE, PartySide.LANDLORD));

    assertThat(next.status()).isEqualTo(RepairStatus.CLOSE_WAIT);
    assertThat(next.closeRequestedBy()).isEqualTo(PartySide.LANDLORD);
  }

  @Test
  void 처리_중이_아니거나_종료_확인_요청을_열_수_없으면_종료를_요청할_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                registered(AckState.ACK), entry(ActionType.REQUEST_CLOSE, PartySide.TENANT)));
    // 거주 종료 뒤에는 종료 확인 요청을 열 수 없다(decisions 2026-11-19)
    RepairState ended =
        new RepairState(
            RepairStatus.IN_PROGRESS,
            AckState.ACK,
            MoneyStatus.UNDECIDED,
            PartySide.TENANT,
            1,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            false,
            null);
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(ended, entry(ActionType.REQUEST_CLOSE, PartySide.TENANT)));
  }

  @Test
  void REP_5a_요청의_상대가_종료를_확인하면_양측_확인으로_끝난다() {
    RepairState next =
        rules.apply(
            closeRequestedBy(PartySide.TENANT),
            entry(ActionType.CONFIRM_CLOSE, PartySide.LANDLORD));

    assertThat(next.status()).isEqualTo(RepairStatus.DONE);
    assertThat(next.closedBy()).isEqualTo(ClosedBy.BOTH);
    assertThat(next.closeRequestedBy()).isNull();
  }

  @Test
  void REP_5b_아직_안_됐어요는_사유와_함께_처리_중으로_돌아간다() {
    RepairState next =
        rules.apply(
            closeRequestedBy(PartySide.TENANT),
            entry(ActionType.NOT_YET, PartySide.LANDLORD, null, "아직 물이 새요", Map.of(), NOW));

    assertThat(next.status()).isEqualTo(RepairStatus.IN_PROGRESS);
    assertThat(next.closeRequestedBy()).isNull();
    assertError(
        ErrorCode.REASON_REQUIRED,
        () ->
            rules.apply(
                closeRequestedBy(PartySide.TENANT),
                entry(ActionType.NOT_YET, PartySide.LANDLORD, null, "  ", Map.of(), NOW)));
  }

  @Test
  void 종료를_요청한_측은_스스로_확인하거나_아직_안_됐어요를_누를_수_없다() {
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () ->
            rules.apply(
                closeRequestedBy(PartySide.TENANT),
                entry(ActionType.CONFIRM_CLOSE, PartySide.TENANT)));
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () ->
            rules.apply(
                closeRequestedBy(PartySide.TENANT),
                entry(ActionType.NOT_YET, PartySide.TENANT, null, "사유", Map.of(), NOW)));
  }

  @Test
  void 종료_확인_대기가_아니면_종료를_확인할_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(inProgress(), entry(ActionType.CONFIRM_CLOSE, PartySide.LANDLORD)));
  }

  @Test
  void 상대가_볼_수_없는_수리_건에는_상대가_수리_진행을_남길_수_없다() {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                registered(AckState.PENDING, true),
                entry(ActionType.START_REPAIR, PartySide.LANDLORD)));
    // 등록한 측은 자기 기록이라 시작할 수 있다
    rules.apply(
        registered(AckState.PENDING, true), entry(ActionType.START_REPAIR, PartySide.TENANT));
  }

  @Test
  void REP_10_수리_진행_이력도_처음부터_다시_계산할_수_있다() {
    List<ActionEntry> history =
        List.of(
            entry(ActionType.REGISTER, PartySide.TENANT),
            entry(ActionType.START_REPAIR, PartySide.LANDLORD),
            entry(ActionType.REQUEST_CLOSE, PartySide.LANDLORD),
            entry(ActionType.NOT_YET, PartySide.TENANT, null, "아직 물이 새요", Map.of(), NOW),
            entry(ActionType.REQUEST_CLOSE, PartySide.TENANT),
            entry(ActionType.CONFIRM_CLOSE, PartySide.LANDLORD));

    RepairState folded = Replay.fold(rules, history);

    assertThat(folded.status()).isEqualTo(RepairStatus.DONE);
    assertThat(folded.closedBy()).isEqualTo(ClosedBy.BOTH);
    assertThat(folded.defectAck()).isEqualTo(AckState.PENDING);
  }

  // ---- 혼자 종료와 혼자 끝낸 건의 늦은 답(REP-5e·5f) ----

  @Test
  void REP_5e_물을_상대가_없으면_처리_중에서_혼자_종료해_처리_완료_그_측_확인이_된다() {
    RepairState next =
        rules.apply(
            inProgress().withCanCloseAlone(true),
            entry(ActionType.CLOSE_ALONE, PartySide.LANDLORD));

    assertThat(next.status()).isEqualTo(RepairStatus.DONE);
    assertThat(next.closedBy()).isEqualTo(ClosedBy.LANDLORD);
    assertThat(next.closedAlone()).isTrue();
  }

  @Test
  void REP_5c_상대가_있으면_혼자_종료는_SOLO_NOT_ALLOWED() {
    assertError(
        ErrorCode.SOLO_NOT_ALLOWED,
        () ->
            rules.apply(
                inProgress().withCanCloseAlone(false),
                entry(ActionType.CLOSE_ALONE, PartySide.TENANT)));
    // 종료 확인 대기는 상대의 답을 기다리는 중이라 같은 오류(decisions 2026-11-25, #228 리뷰)
    assertError(
        ErrorCode.SOLO_NOT_ALLOWED,
        () ->
            rules.apply(
                closeRequestedBy(PartySide.TENANT).withCanCloseAlone(true),
                entry(ActionType.CLOSE_ALONE, PartySide.TENANT)));
  }

  @Test
  void 시작_전이나_처리_완료에서는_혼자_종료가_INVALID_STATE() {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                registered(AckState.ACK).withCanCloseAlone(true),
                entry(ActionType.CLOSE_ALONE, PartySide.TENANT)));
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                closedAloneBy(ClosedBy.TENANT).withCanCloseAlone(true),
                entry(ActionType.CLOSE_ALONE, PartySide.TENANT)));
  }

  @Test
  void REP_5f_혼자_끝낸_건에_상대가_늦게_종료를_확인하면_양측_확인이_된다() {
    RepairState next =
        rules.apply(
            closedAloneBy(ClosedBy.LANDLORD), entry(ActionType.CONFIRM_CLOSE, PartySide.TENANT));

    assertThat(next.status()).isEqualTo(RepairStatus.DONE);
    assertThat(next.closedBy()).isEqualTo(ClosedBy.BOTH);
  }

  @Test
  void REP_5f_혼자_끝낸_건에_상대의_아직_안_됐어요는_상태를_바꾸지_않고_사유가_필수다() {
    RepairState done = closedAloneBy(ClosedBy.LANDLORD);
    RepairState next =
        rules.apply(
            done, entry(ActionType.NOT_YET, PartySide.TENANT, null, "아직 물이 새요", Map.of(), NOW));

    assertThat(next).isEqualTo(done);
    assertError(
        ErrorCode.REASON_REQUIRED,
        () -> rules.apply(done, entry(ActionType.NOT_YET, PartySide.TENANT)));
    // 이견 뒤에도 늦은 종료 확인은 된다
    assertThat(rules.apply(next, entry(ActionType.CONFIRM_CLOSE, PartySide.TENANT)).closedBy())
        .isEqualTo(ClosedBy.BOTH);
  }

  @Test
  void 혼자_끝낸_측은_스스로_확인하거나_이견을_남길_수_없고_양측_확인_뒤에는_답할_수_없다() {
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () ->
            rules.apply(
                closedAloneBy(ClosedBy.LANDLORD),
                entry(ActionType.CONFIRM_CLOSE, PartySide.LANDLORD)));
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () ->
            rules.apply(
                closedAloneBy(ClosedBy.LANDLORD),
                entry(ActionType.NOT_YET, PartySide.LANDLORD, null, "사유", Map.of(), NOW)));
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                closedAloneBy(ClosedBy.BOTH),
                entry(ActionType.NOT_YET, PartySide.TENANT, null, "사유", Map.of(), NOW)));
  }

  @Test
  void REP_10_혼자_종료와_이견과_늦은_확인도_처음부터_다시_계산할_수_있다() {
    List<ActionEntry> history =
        List.of(
            entry(ActionType.REGISTER, PartySide.TENANT),
            entry(ActionType.START_REPAIR, PartySide.TENANT),
            entry(ActionType.CLOSE_ALONE, PartySide.TENANT),
            entry(ActionType.NOT_YET, PartySide.LANDLORD, null, "아직 물이 새요", Map.of(), NOW),
            entry(ActionType.CONFIRM_CLOSE, PartySide.LANDLORD));

    RepairState folded = Replay.fold(rules, history);

    assertThat(folded.status()).isEqualTo(RepairStatus.DONE);
    assertThat(folded.closedBy()).isEqualTo(ClosedBy.BOTH);
  }

  @Test
  void 혼자_종료_버튼은_혼자_종료할_수_있다고_확인됐을_때만_보인다() {
    assertThat(rules.available(inProgress().withCanCloseAlone(true), PartySide.TENANT, NOW))
        .contains(ActionType.CLOSE_ALONE);
    assertThat(rules.available(inProgress().withCanCloseAlone(false), PartySide.TENANT, NOW))
        .doesNotContain(ActionType.CLOSE_ALONE);
    assertThat(rules.available(inProgress(), PartySide.TENANT, NOW))
        .doesNotContain(ActionType.CLOSE_ALONE);
    assertThat(rules.available(closedAloneBy(ClosedBy.LANDLORD), PartySide.TENANT, NOW))
        .contains(ActionType.CONFIRM_CLOSE, ActionType.NOT_YET);
    assertThat(rules.available(closedAloneBy(ClosedBy.LANDLORD), PartySide.LANDLORD, NOW))
        .doesNotContain(ActionType.CONFIRM_CLOSE, ActionType.NOT_YET);
  }

  /** 처리 완료(끝낸 측). */
  private static RepairState closedAloneBy(ClosedBy closedBy) {
    return registered(AckState.ACK).withProgress(RepairStatus.DONE, null, closedBy);
  }

  /** 세입자가 등록하고 시작한 수리 건(종료 확인 요청은 열 수 있음). */
  private static RepairState inProgress() {
    return registered(AckState.ACK).withProgress(RepairStatus.IN_PROGRESS, null, null);
  }

  private static RepairState closeRequestedBy(PartySide side) {
    return registered(AckState.ACK).withProgress(RepairStatus.CLOSE_WAIT, side, null);
  }

  // ---- 진행 상황 묻기·남기기(REP-6) ----

  @Test
  void 진행_상황을_물으면_물은_시각만_남고_수리_진행과_하자_인정은_그대로다() {
    RepairState next =
        rules.apply(inProgress(), entry(ActionType.ASK_PROGRESS, PartySide.LANDLORD));

    assertThat(next.lastProgressAskAt()).isEqualTo(NOW);
    assertThat(next.withLastProgressAskAt(null)).isEqualTo(inProgress());
    assertThat(rules.nextProgressAskAt(next)).isEqualTo(NOW.plus(INTERVAL));
  }

  @Test
  void REP_6b_누가_물었든_7일_안에_다시_물으면_ASKED_RECENTLY이고_7일이_지나면_된다() {
    RepairState asked = inProgress().withLastProgressAskAt(NOW.minus(Duration.ofDays(3)));

    assertError(
        ErrorCode.ASKED_RECENTLY,
        () -> rules.apply(asked, entry(ActionType.ASK_PROGRESS, PartySide.TENANT)));
    assertError(
        ErrorCode.ASKED_RECENTLY,
        () -> rules.apply(asked, entry(ActionType.ASK_PROGRESS, PartySide.LANDLORD)));
    RepairState weekAgo = inProgress().withLastProgressAskAt(NOW.minus(INTERVAL));
    assertThat(
            rules
                .apply(weekAgo, entry(ActionType.ASK_PROGRESS, PartySide.TENANT))
                .lastProgressAskAt())
        .isEqualTo(NOW);
  }

  @Test
  void 진행_상황은_시작_전과_처리_중에만_물을_수_있다() {
    assertThat(
            rules
                .apply(
                    registered(AckState.PENDING), entry(ActionType.ASK_PROGRESS, PartySide.TENANT))
                .lastProgressAskAt())
        .isEqualTo(NOW);
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                closeRequestedBy(PartySide.TENANT),
                entry(ActionType.ASK_PROGRESS, PartySide.LANDLORD)));
    RepairState done = inProgress().withProgress(RepairStatus.DONE, null, ClosedBy.BOTH);
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(done, entry(ActionType.ASK_PROGRESS, PartySide.LANDLORD)));
  }

  @Test
  void 진행_상황_묻기_요청을_열_수_없으면_거주_종료_뒤라_물을_수_없다() {
    RepairState s = inProgress();
    RepairState ended =
        new RepairState(
            s.status(),
            s.defectAck(),
            s.moneyStatus(),
            s.registeredBy(),
            s.contentNo(),
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            null);

    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(ended, entry(ActionType.ASK_PROGRESS, PartySide.TENANT)));
    // 남기기는 언제든 된다(03 D)
    assertThat(rules.apply(ended, entry(ActionType.NOTE_PROGRESS, PartySide.TENANT)))
        .isEqualTo(ended);
  }

  @Test
  void 진행_상황은_처리_완료_뒤에도_남길_수_있고_상태는_그대로다() {
    RepairState done = inProgress().withProgress(RepairStatus.DONE, null, ClosedBy.BOTH);

    assertThat(rules.apply(done, entry(ActionType.NOTE_PROGRESS, PartySide.LANDLORD)))
        .isEqualTo(done);
  }

  @Test
  void 상대가_볼_수_없는_기록에는_상대가_진행_상황을_묻거나_남길_수_없다() {
    RepairState hidden = registered(AckState.PENDING, true);

    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(hidden, entry(ActionType.NOTE_PROGRESS, PartySide.LANDLORD)));
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(hidden, entry(ActionType.ASK_PROGRESS, PartySide.LANDLORD)));
    assertThat(rules.apply(hidden, entry(ActionType.NOTE_PROGRESS, PartySide.TENANT)))
        .isEqualTo(hidden);
  }

  @Test
  void REP_10_진행_상황_묻기도_처음부터_다시_계산하면_7일_간격을_지킨_기록이다() {
    List<ActionEntry> history =
        List.of(
            entry(ActionType.REGISTER, PartySide.TENANT),
            entry(ActionType.ASK_PROGRESS, PartySide.TENANT, null, null, Map.of(), NOW),
            entry(ActionType.NOTE_PROGRESS, PartySide.LANDLORD),
            entry(
                ActionType.ASK_PROGRESS,
                PartySide.LANDLORD,
                null,
                null,
                Map.of(),
                NOW.plus(INTERVAL)));

    assertThat(Replay.fold(rules, history).lastProgressAskAt()).isEqualTo(NOW.plus(INTERVAL));
  }

  // ---- 할 수 있는 행동(availableActions) ----

  /**
   * 수리 진행 × 종료 요청한 측 × 종료 확인 요청을 열 수 있음 × 하자 인정 × 상대에게 보임 × 하자 확인 요청을 열 수 있음 × 마지막 보충(없음·3일 전·7일
   * 전). 처리 완료면 끝낸 측을 채운다(V1 CHECK와 같은 모양).
   */
  static List<RepairState> availableCases() {
    Instant[] lastSupplements = {null, NOW.minus(Duration.ofDays(3)), NOW.minus(INTERVAL)};
    PartySide[] requesters = {null, PartySide.TENANT, PartySide.LANDLORD};
    List<RepairState> cases = new ArrayList<>();
    for (RepairStatus status : RepairStatus.values()) {
      for (PartySide requester : requesters) {
        for (boolean canOpenClose : new boolean[] {true, false}) {
          for (AckState ack : AckState.values()) {
            for (boolean hidden : new boolean[] {true, false}) {
              for (boolean canOpen : new boolean[] {true, false}) {
                for (Instant last : lastSupplements) {
                  for (Instant lastAsk : lastSupplements) {
                    for (boolean canAsk : new boolean[] {true, false}) {
                      cases.add(
                          new RepairState(
                              status,
                              ack,
                              MoneyStatus.UNDECIDED,
                              PartySide.TENANT,
                              1,
                              last,
                              status == RepairStatus.DONE ? ClosedBy.BOTH : null,
                              requester,
                              lastAsk,
                              canOpen,
                              canOpenClose,
                              canAsk,
                              hidden,
                              null));
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    // 혼자 종료할 수 있음(상세가 채우는 값) × 처리 완료면 끝낸 측(양측·세입자·집주인)
    List<RepairState> withSolo = new ArrayList<>();
    for (RepairState base : cases) {
      List<RepairState> closings = new ArrayList<>(List.of(base));
      if (base.status() == RepairStatus.DONE) {
        closings.add(base.withProgress(RepairStatus.DONE, null, ClosedBy.TENANT));
        closings.add(base.withProgress(RepairStatus.DONE, null, ClosedBy.LANDLORD));
      }
      for (RepairState closing : closings) {
        withSolo.add(closing.withCanCloseAlone(true));
        withSolo.add(closing.withCanCloseAlone(false));
      }
    }
    return withSolo;
  }

  @Test
  void 보여_주는_버튼은_apply가_받아_주는_행동과_같다() {
    for (RepairState state : availableCases()) {
      for (PartySide side : PartySide.values()) {
        List<ActionType> available = rules.available(state, side, NOW);
        // 맞아요가 되는지(보류·이견에서도 맞아요로는 바꿀 수 있어 버튼이 보인다). 아직 안 됐어요는 사유가 있다고 본다(근거를 더한다고 보는 보충과 같음)
        Map<ActionType, ActionEntry> tries =
            Map.of(
                ActionType.RESPOND_DEFECT, respond(side, Answer.ACK, null, 1),
                ActionType.SUPPLEMENT, supplement(side, NOW),
                ActionType.START_REPAIR, entry(ActionType.START_REPAIR, side),
                ActionType.REQUEST_CLOSE, entry(ActionType.REQUEST_CLOSE, side),
                ActionType.CONFIRM_CLOSE, entry(ActionType.CONFIRM_CLOSE, side),
                ActionType.NOT_YET,
                    entry(ActionType.NOT_YET, side, null, "아직 물이 새요", Map.of(), NOW),
                ActionType.CLOSE_ALONE, entry(ActionType.CLOSE_ALONE, side),
                ActionType.ASK_PROGRESS,
                    entry(ActionType.ASK_PROGRESS, side, null, null, Map.of(), NOW),
                ActionType.NOTE_PROGRESS, entry(ActionType.NOTE_PROGRESS, side));
        tries.forEach((type, action) ->
            assertThat(available.contains(type))
                .as("%s %s %s", type, side, state)
                .isEqualTo(accepts(() -> rules.apply(state, action))));
      }
    }
  }

  @Test
  void 없는_기록이나_측이_없으면_할_수_있는_행동이_없다() {
    assertThat(rules.available(RepairState.NONE, PartySide.TENANT, NOW)).isEmpty();
    assertThat(rules.available(registered(AckState.PENDING), null, NOW)).isEmpty();
  }

  @Test
  void 다음_보충_시각은_마지막_보충에서_7일_뒤고_보충한_적이_없으면_없다() {
    RepairState never = registered(AckState.NEED_MORE);
    RepairState supplemented = never.withLastRerequestAt(NOW);

    assertThat(rules.nextRerequestAt(never)).isNull();
    assertThat(rules.nextRerequestAt(supplemented)).isEqualTo(NOW.plus(INTERVAL));
  }

  private static boolean accepts(Runnable call) {
    try {
      call.run();
      return true;
    } catch (BusinessException e) {
      return false;
    }
  }

  // ---- REP-6d [상황이 바뀌었어요] ----

  @Test
  void 하자를_둘_다_확인한_건에서_상황이_바뀌었어요를_켜면_켠_시각이_남고_하자_인정은_그대로다() {
    RepairState next =
        rules.apply(
            situation(AckState.ACK, null, SituationOpening.OPEN),
            asksCheck(PartySide.LANDLORD, NOW));

    assertThat(next.situationCheck().lastAskedAt()).isEqualTo(NOW);
    assertThat(next.defectAck()).isEqualTo(AckState.ACK);
    assertThat(next.status()).isEqualTo(RepairStatus.IN_PROGRESS);
  }

  @ParameterizedTest
  @EnumSource(
      value = AckState.class,
      names = {"PENDING", "NEED_MORE", "DISPUTE", "UNCONFIRMED"})
  void 하자를_둘_다_확인하지_않았으면_상황이_바뀌었어요는_INVALID_STATE(AckState ack) {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                situation(ack, null, SituationOpening.OPEN), asksCheck(PartySide.TENANT, NOW)));
  }

  @Test
  void 켜지_않은_진행_상황은_하자_인정과_상관없이_남는다() {
    RepairState now = situation(AckState.PENDING, NOW, SituationOpening.BLOCKED);

    assertThat(rules.apply(now, entry(ActionType.NOTE_PROGRESS, PartySide.TENANT))).isEqualTo(now);
  }

  @Test
  void 요청을_열_수_없으면_INVALID_STATE이고_공유를_그만둔_사람은_하자_인정보다_먼저_SHARING_STOPPED() {
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                situation(AckState.ACK, null, SituationOpening.BLOCKED),
                asksCheck(PartySide.TENANT, NOW)));
    assertError(
        ErrorCode.SHARING_STOPPED,
        () ->
            rules.apply(
                situation(AckState.PENDING, null, SituationOpening.SHARING_STOPPED),
                asksCheck(PartySide.TENANT, NOW)));
  }

  @Test
  void 누가_켰든_한_건에_7일에_한_번이고_딱_7일이_지나면_된다() {
    RepairState asked = situation(AckState.ACK, NOW, SituationOpening.OPEN);

    assertError(
        ErrorCode.ASKED_RECENTLY,
        () -> rules.apply(asked, asksCheck(PartySide.TENANT, NOW.plus(Duration.ofDays(6)))));
    Instant later = NOW.plus(INTERVAL);
    assertThat(
            rules.apply(asked, asksCheck(PartySide.LANDLORD, later)).situationCheck().lastAskedAt())
        .isEqualTo(later);
  }

  @Test
  void 보충과_상황이_바뀌었어요는_다시_요청끼리_함께_7일을_센다() {
    // 03 G "다시 확인 요청의 한도", 06 0-1 rerequest.interval(#259 리뷰): 보충하고 7일 안에 맞아요가 와도 그 7일이 지나야 켠다
    RepairState supplemented =
        situation(AckState.ACK, null, SituationOpening.OPEN).withLastRerequestAt(NOW);

    assertError(
        ErrorCode.ASKED_RECENTLY,
        () -> rules.apply(supplemented, asksCheck(PartySide.TENANT, NOW.plus(Duration.ofDays(6)))));
    Instant later = NOW.plus(INTERVAL);
    assertThat(
            rules
                .apply(supplemented, asksCheck(PartySide.TENANT, later))
                .situationCheck()
                .lastAskedAt())
        .isEqualTo(later);
  }

  @Test
  void 재계산이라_열_수_있는지_모르면_그때_서버가_확인했다고_본다() {
    RepairState replayed = situation(AckState.ACK, null, null);

    assertThat(
            rules.apply(replayed, asksCheck(PartySide.TENANT, NOW)).situationCheck().lastAskedAt())
        .isEqualTo(NOW);
  }

  @Test
  void 재계산은_지금_설정으로_과거에_켠_상황이_바뀌었어요를_다시_거절하지_않는다() {
    // 7일 설정에서 8일 간격으로 기록된 행동을 14일 설정으로 다시 계산해도 실패하지 않는다(#234와 같은 방식, #259 리뷰)
    RepairTransitions longer = new RepairTransitions(Duration.ofDays(14), INTERVAL);
    List<ActionEntry> history =
        List.of(
            entry(ActionType.REGISTER, PartySide.TENANT),
            respond(PartySide.LANDLORD, Answer.ACK, null, 1),
            asksCheck(PartySide.TENANT, NOW),
            asksCheck(PartySide.LANDLORD, NOW.plus(Duration.ofDays(8))));

    RepairState folded = Replay.fold(longer, history);

    assertThat(folded.situationCheck().lastAskedAt()).isEqualTo(NOW.plus(Duration.ofDays(8)));
    // 요청할 때는 지금 설정(14일)으로 막는다
    assertError(
        ErrorCode.ASKED_RECENTLY,
        () ->
            longer.apply(
                folded.withSituationCheck(
                    folded.situationCheck().withOpening(SituationOpening.OPEN)),
                asksCheck(PartySide.TENANT, NOW.plus(Duration.ofDays(20)))));
  }

  /** 세입자가 등록하고 처리 중인 수리 건. [상황이 바뀌었어요]를 마지막으로 켠 시각과 지금 열 수 있는지를 정한다. */
  private static RepairState situation(
      AckState ack, Instant lastAskedAt, SituationOpening opening) {
    return registered(ack)
        .withProgress(RepairStatus.IN_PROGRESS, null, null)
        .withSituationCheck(new SituationCheck(lastAskedAt, opening));
  }

  private static ActionEntry asksCheck(PartySide side, Instant at) {
    return entry(
        ActionType.NOTE_PROGRESS,
        side,
        null,
        null,
        Map.of(RepairTransitions.ProgressPayload.ASKS_CHECK, true),
        at);
  }

  // ---- 아직 없는 행동 ----

  /** 아직 만들지 않은 수리 행동. 새 행동을 붙이면 여기서 빼고 따로 테스트한다. */
  static Stream<ActionType> notYetRepairActions() {
    Set<ActionType> done =
        Set.of(
            ActionType.REGISTER,
            ActionType.RESPOND_DEFECT,
            ActionType.SUPPLEMENT,
            ActionType.START_REPAIR,
            ActionType.REQUEST_CLOSE,
            ActionType.CONFIRM_CLOSE,
            ActionType.NOT_YET,
            ActionType.CLOSE_ALONE,
            ActionType.ASK_PROGRESS,
            ActionType.NOTE_PROGRESS);
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
        null,
        null,
        null,
        true,
        true,
        true,
        hiddenFromCounterpart,
        null);
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
