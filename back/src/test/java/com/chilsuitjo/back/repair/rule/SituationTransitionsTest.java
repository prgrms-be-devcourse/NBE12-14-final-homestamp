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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 상황 변화 응답 규칙(06 REP-6d, 03 G). 순수 함수라 pipeline 없이 부른다(안내서 5-1). */
class SituationTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");

  private final SituationTransitions rules = new SituationTransitions();

  @Test
  void 진행_상황을_남긴_측의_상대가_맞아요나_보류_이견으로_답한다() {
    assertThat(
            rules
                .apply(asked(AckState.PENDING), respond(PartySide.LANDLORD, Answer.ACK, null))
                .answer())
        .isEqualTo(AckState.ACK);
    assertThat(
            rules
                .apply(
                    asked(AckState.PENDING),
                    respond(PartySide.LANDLORD, Answer.NEED_MORE, "사진이 흐려요"))
                .answer())
        .isEqualTo(AckState.NEED_MORE);
    assertThat(
            rules
                .apply(
                    asked(AckState.UNCONFIRMED),
                    respond(PartySide.LANDLORD, Answer.DISPUTE, "원래 있던 자국이에요"))
                .answer())
        .isEqualTo(AckState.DISPUTE);
  }

  @Test
  void 응답_버튼은_상황_변화_확인_요청에_상대_측이_맞아요_전일_때만_켜진다() {
    // 수리 건 상세 progressNotes[].canRespond(06 4-6, #367)는 apply가 맞아요로 성공하는지와 같다
    assertThat(rules.canRespond(asked(AckState.PENDING), PartySide.LANDLORD)).isTrue();
    assertThat(rules.canRespond(asked(AckState.NEED_MORE), PartySide.LANDLORD)).isTrue();
    assertThat(rules.canRespond(asked(AckState.ACK), PartySide.LANDLORD)).isFalse();
    assertThat(rules.canRespond(asked(AckState.PENDING), PartySide.TENANT)).isFalse();
    assertThat(rules.canRespond(asked(AckState.PENDING), null)).isFalse();
    assertThat(
            rules.canRespond(
                new SituationState(true, PartySide.TENANT, false, AckState.PENDING),
                PartySide.LANDLORD))
        .isFalse();
  }

  @Test
  void 진행_상황을_남긴_측은_답할_수_없다() {
    assertError(
        ErrorCode.NOT_COUNTERPART_SIDE,
        () -> rules.apply(asked(AckState.PENDING), respond(PartySide.TENANT, Answer.ACK, null)));
  }

  @Test
  void 맞아요는_끝이고_보류_이견에서는_맞아요로만_바꾸며_보류_이견은_사유가_필요하다() {
    assertError(
        ErrorCode.ACK_FINAL,
        () -> rules.apply(asked(AckState.ACK), respond(PartySide.LANDLORD, Answer.NEED_MORE, "x")));
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                asked(AckState.NEED_MORE), respond(PartySide.LANDLORD, Answer.DISPUTE, "x")));
    assertThat(
            rules
                .apply(asked(AckState.DISPUTE), respond(PartySide.LANDLORD, Answer.ACK, null))
                .answer())
        .isEqualTo(AckState.ACK);
    assertError(
        ErrorCode.REASON_REQUIRED,
        () ->
            rules.apply(
                asked(AckState.PENDING), respond(PartySide.LANDLORD, Answer.NEED_MORE, " ")));
  }

  @Test
  void 상황이_바뀌었어요로_남긴_진행_상황이_아니면_INVALID_STATE() {
    SituationState plain = new SituationState(true, PartySide.TENANT, false, AckState.PENDING);

    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(plain, respond(PartySide.LANDLORD, Answer.ACK, null)));
  }

  @Test
  void 측이_없는_주체나_다른_행동은_INVALID_STATE() {
    assertError(
        ErrorCode.INVALID_STATE,
        () -> rules.apply(asked(AckState.PENDING), respond(null, Answer.ACK, null)));
    assertError(
        ErrorCode.INVALID_STATE,
        () ->
            rules.apply(
                asked(AckState.PENDING),
                entry(ActionType.RESPOND_CAUSE, PartySide.LANDLORD, null, Map.of())));
  }

  /** 세입자가 [상황이 바뀌었어요]로 남긴 진행 상황. */
  private static SituationState asked(AckState answer) {
    return new SituationState(true, PartySide.TENANT, true, answer);
  }

  private static ActionEntry respond(PartySide side, Answer answer, String reason) {
    return entry(ActionType.RESPOND_SITUATION, side, reason, Map.of("answer", answer.name()));
  }

  private static ActionEntry entry(
      ActionType type, PartySide side, String reason, Map<String, Object> payload) {
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
        null,
        null,
        reason,
        payload,
        NOW);
  }

  private static void assertError(ErrorCode code, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }
}
