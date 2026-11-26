package com.chilsuitjo.back.inspection.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 점검 항목 응답·보충·모두 확인 규칙(06 4-7, 03 H "상대의 확인", #235). 순수 함수라 pipeline 없이 부른다(안내서 5-1). */
class InspectionAnswerTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
  private static final Duration INTERVAL = Duration.ofDays(7);

  private final InspectionItemTransitions rules = new InspectionItemTransitions(INTERVAL);
  private final ConfirmAllTransitions confirmAll = new ConfirmAllTransitions();

  // ---- 응답 ----

  @Test
  void 확인_요청_뒤_상대는_본_판으로_맞아요를_남긴다() {
    InspectionItemState next =
        rules.apply(item(AckState.PENDING), respond(PartySide.LANDLORD, "ACK", null, 1));

    assertThat(next.status()).isEqualTo(AckState.ACK);
    assertThat(InspectionItemTransitions.canRespond(next, PartySide.LANDLORD)).isFalse();
  }

  @Test
  void 확인_없음_항목에도_답할_수_있다() {
    InspectionItemState next =
        rules.apply(
            item(AckState.UNCONFIRMED), respond(PartySide.LANDLORD, "DISPUTE", "사실과 달라요", 1));

    assertThat(next.status()).isEqualTo(AckState.DISPUTE);
  }

  @Test
  void 작성_중에는_답할_수_없다() {
    InspectionItemState draft = item(InspectionStatus.DRAFT, AckState.PENDING, null, false);

    assertThatThrownBy(() -> rules.apply(draft, respond(PartySide.LANDLORD, "ACK", null, 1)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(InspectionItemTransitions.canRespond(draft, PartySide.LANDLORD)).isFalse();
  }

  @Test
  void 불변식_18_답하는_사람에게_보이지_않는_항목에는_답할_수_없다() {
    InspectionItemState hidden = item(InspectionStatus.REQUESTED, AckState.PENDING, null, true);

    assertThatThrownBy(() -> rules.apply(hidden, respond(PartySide.LANDLORD, "ACK", null, 1)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(InspectionItemTransitions.canRespond(hidden, PartySide.LANDLORD)).isFalse();
  }

  @Test
  void 응답은_공통_규칙_순서를_따른다() {
    InspectionItemState pending = item(AckState.PENDING);

    assertThatThrownBy(() -> rules.apply(pending, respond(PartySide.TENANT, "ACK", null, 1)))
        .hasMessage(ErrorCode.NOT_COUNTERPART_SIDE.name());
    assertThatThrownBy(() -> rules.apply(pending, respond(PartySide.LANDLORD, "ACK", null, 2)))
        .hasMessage(ErrorCode.VERSION_CONFLICT.name());
    assertThatThrownBy(() -> rules.apply(pending, respond(PartySide.LANDLORD, "NEED_MORE", " ", 1)))
        .hasMessage(ErrorCode.REASON_REQUIRED.name());
    assertThatThrownBy(
            () -> rules.apply(item(AckState.ACK), respond(PartySide.LANDLORD, "DISPUTE", "r", 1)))
        .hasMessage(ErrorCode.ACK_FINAL.name());
    assertThatThrownBy(() ->
            rules.apply(item(AckState.NEED_MORE), respond(PartySide.LANDLORD, "DISPUTE", "r", 1)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  @Test
  void 잠긴_뒤에도_늦게_답할_수_있다() {
    InspectionItemState locked = item(InspectionStatus.LOCKED, AckState.PENDING, null, false);

    assertThat(rules.apply(locked, respond(PartySide.LANDLORD, "ACK", null, 1)).status())
        .isEqualTo(AckState.ACK);
  }

  @Test
  void 잠긴_뒤에는_보충할_수_없고_늦은_응답만_된다() {
    // 03 H "잠긴 뒤": 내용은 고치기로만 바꾸고, 미확인이었던 항목에는 늦게 답할 수 있다(#272 리뷰)
    InspectionItemState locked = item(InspectionStatus.LOCKED, AckState.NEED_MORE, null, false);

    assertThatThrownBy(() -> rules.apply(locked, supplement(PartySide.TENANT, NOW)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(rules.canSupplement(locked, PartySide.TENANT, NOW)).isFalse();
    assertThat(rules.apply(locked, respond(PartySide.LANDLORD, "ACK", null, 1)).status())
        .isEqualTo(AckState.ACK);
    // 같은 항목도 확인 요청 중이면 보충된다
    assertThat(rules.canSupplement(item(AckState.NEED_MORE), PartySide.TENANT, NOW)).isTrue();
  }

  // ---- 보충 ----

  @Test
  void 보류_뒤_더한_측이_보충하면_확인_기다림으로_돌아가고_시각이_남는다() {
    InspectionItemState next =
        rules.apply(item(AckState.NEED_MORE), supplement(PartySide.TENANT, NOW));

    assertThat(next.status()).isEqualTo(AckState.PENDING);
    assertThat(next.lastRerequestAt()).isEqualTo(NOW);
    assertThat(rules.nextRerequestAt(next)).isEqualTo(NOW.plus(INTERVAL));
  }

  @Test
  void 보충은_더한_측만_보류_이견에서만_7일에_한_번() {
    assertThatThrownBy(
            () -> rules.apply(item(AckState.DISPUTE), supplement(PartySide.LANDLORD, NOW)))
        .hasMessage(ErrorCode.NOT_COUNTERPART_SIDE.name());
    assertThatThrownBy(() -> rules.apply(item(AckState.PENDING), supplement(PartySide.TENANT, NOW)))
        .hasMessage(ErrorCode.INVALID_STATE.name());

    InspectionItemState recent =
        item(AckState.DISPUTE).withLastRerequestAt(NOW.minus(INTERVAL).plusSeconds(1));
    assertThatThrownBy(() -> rules.apply(recent, supplement(PartySide.TENANT, NOW)))
        .hasMessage(ErrorCode.ASKED_RECENTLY.name());
    assertThat(rules.canSupplement(recent, PartySide.TENANT, NOW)).isFalse();

    InspectionItemState due = item(AckState.DISPUTE).withLastRerequestAt(NOW.minus(INTERVAL));
    assertThat(rules.apply(due, supplement(PartySide.TENANT, NOW)).status())
        .isEqualTo(AckState.PENDING);
  }

  @Test
  void 점검_확인_요청을_열_수_없는_단계면_보충할_수_없다() {
    InspectionItemState blocked =
        new InspectionItemState(
            InspectionStatus.REQUESTED,
            PartySide.TENANT,
            AckState.NEED_MORE,
            PartySide.TENANT,
            1,
            null,
            false,
            false);

    assertThatThrownBy(() -> rules.apply(blocked, supplement(PartySide.TENANT, NOW)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  // ---- 모두 확인 ----

  @Test
  void 모두_확인은_본_항목_판이_지금_미응답과_같아야_한다() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    ConfirmAllState state = confirmAllState(Map.of(a, 1, b, 2), 3, false, false);

    assertThat(confirmAll.apply(state, confirmAllEntry(PartySide.LANDLORD, Map.of(a, 1, b, 2))))
        .isEqualTo(state);
    assertThatThrownBy(
            () -> confirmAll.apply(state, confirmAllEntry(PartySide.LANDLORD, Map.of(a, 1, b, 1))))
        .hasMessage(ErrorCode.VERSION_CONFLICT.name());
    assertThatThrownBy(
            () -> confirmAll.apply(state, confirmAllEntry(PartySide.LANDLORD, Map.of(a, 1))))
        .hasMessage(ErrorCode.VERSION_CONFLICT.name());
    assertThat(ConfirmAllTransitions.canConfirmAll(state, PartySide.LANDLORD)).isTrue();
  }

  @Test
  void 항목이_없으면_기준_측이_아닌_사람이_한_번_하자_기록_없음을_확인한다() {
    ConfirmAllState empty = confirmAllState(Map.of(), 0, false, false);

    assertThat(confirmAll.apply(empty, confirmAllEntry(PartySide.LANDLORD, Map.of())))
        .isEqualTo(empty);
    assertThatThrownBy(() -> confirmAll.apply(empty, confirmAllEntry(PartySide.TENANT, Map.of())))
        .hasMessage(ErrorCode.NOT_COUNTERPART_SIDE.name());

    ConfirmAllState done = confirmAllState(Map.of(), 0, false, true);
    assertThatThrownBy(() -> confirmAll.apply(done, confirmAllEntry(PartySide.LANDLORD, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(ConfirmAllTransitions.canConfirmAll(done, PartySide.LANDLORD)).isFalse();
  }

  @Test
  void 답할_항목이_남지_않았거나_가려졌거나_작성_중이면_INVALID_STATE() {
    ConfirmAllState answered = confirmAllState(Map.of(), 2, false, false);
    assertThatThrownBy(
            () -> confirmAll.apply(answered, confirmAllEntry(PartySide.LANDLORD, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(ConfirmAllTransitions.canConfirmAll(answered, PartySide.LANDLORD)).isFalse();

    ConfirmAllState hidden = confirmAllState(Map.of(), 0, true, false);
    assertThatThrownBy(
            () -> confirmAll.apply(hidden, confirmAllEntry(PartySide.LANDLORD, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());

    ConfirmAllState draft =
        new ConfirmAllState(InspectionStatus.DRAFT, PartySide.TENANT, Map.of(), 0, false, false);
    assertThatThrownBy(() -> confirmAll.apply(draft, confirmAllEntry(PartySide.LANDLORD, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  @Test
  void 보이지_않는_항목이_있으면_하자_기록_없음으로_확인할_수_없다() {
    // 보이는 항목은 0개지만 점검에는 항목이 있다(상대 더하기 INSP-1d 등). 전체 항목 수로 본다(#272 리뷰)
    ConfirmAllState unseen = confirmAllState(Map.of(), 1, false, false);

    assertThat(unseen.noItems()).isFalse();
    assertThatThrownBy(
            () -> confirmAll.apply(unseen, confirmAllEntry(PartySide.LANDLORD, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(ConfirmAllTransitions.canConfirmAll(unseen, PartySide.LANDLORD)).isFalse();
  }

  // ---- 도우미 ----

  /** 세입자가 기준 측으로 더한 항목, 점검은 확인 요청. */
  private static InspectionItemState item(AckState status) {
    return item(InspectionStatus.REQUESTED, status, null, false);
  }

  private static InspectionItemState item(
      InspectionStatus inspection, AckState status, Instant lastRerequestAt, boolean hidden) {
    return new InspectionItemState(
        inspection, PartySide.TENANT, status, PartySide.TENANT, 1, lastRerequestAt, true, hidden);
  }

  private static ConfirmAllState confirmAllState(
      Map<UUID, Integer> unanswered, int total, boolean hidden, boolean confirmed) {
    return new ConfirmAllState(
        InspectionStatus.REQUESTED, PartySide.TENANT, unanswered, total, hidden, confirmed);
  }

  private static ActionEntry respond(PartySide side, String answer, String reason, Integer seen) {
    return entry(ActionType.RESPOND_ITEM, side, seen, reason, Map.of("answer", answer), NOW);
  }

  private static ActionEntry supplement(PartySide side, Instant at) {
    return entry(ActionType.SUPPLEMENT_ITEM, side, null, null, Map.of(), at);
  }

  private static ActionEntry confirmAllEntry(PartySide side, Map<UUID, Integer> seen) {
    List<Map<String, Object>> items =
        seen.entrySet().stream()
            .<Map<String, Object>>map(e ->
                Map.of(
                    ConfirmAllTransitions.ITEM_ID,
                    e.getKey().toString(),
                    ConfirmAllTransitions.SEEN_VERSION_NO,
                    e.getValue()))
            .toList();
    return entry(
        ActionType.CONFIRM_ALL, side, null, null, Map.of(ConfirmAllTransitions.ITEMS, items), NOW);
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
