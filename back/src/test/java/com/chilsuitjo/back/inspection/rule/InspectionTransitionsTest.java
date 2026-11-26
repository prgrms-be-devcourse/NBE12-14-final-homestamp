package com.chilsuitjo.back.inspection.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 점검·점검 항목 규칙(03 H, 06 INSP-1a·2b). 순수 함수라 pipeline 없이 부른다(안내서 5-1). */
class InspectionTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");

  private static final Duration STALE = Duration.ofDays(7);

  private final InspectionTransitions rules = new InspectionTransitions(STALE);
  private final InspectionItemTransitions itemRules = new InspectionItemTransitions();

  // ---- 점검 시작 ----

  @Test
  void 시작하면_작성_중이고_시작한_측이_기준이다() {
    InspectionState next =
        rules.apply(
            InspectionState.notStarted(false, false), start(PartySide.LANDLORD, "MOVE_IN", null));

    assertThat(next.kind()).isEqualTo(InspectionKind.MOVE_IN);
    assertThat(next.status()).isEqualTo(InspectionStatus.DRAFT);
    assertThat(next.authorSide()).isEqualTo(PartySide.LANDLORD);
  }

  @Test
  void 이미_같은_종류_점검이_있으면_INSPECTION_EXISTS() {
    InspectionState existing = draft(PartySide.TENANT, NOW, null);

    assertThatThrownBy(() -> rules.apply(existing, start(PartySide.LANDLORD, "MOVE_IN", null)))
        .hasMessage(ErrorCode.INSPECTION_EXISTS.name());
  }

  // ---- 확인 요청(INSP-1f) ----

  @Test
  void 기준_측은_작성_중이면_바로_확인_요청을_보낸다() {
    InspectionState next =
        rules.apply(draft(PartySide.TENANT, NOW, true), request(PartySide.TENANT, NOW));

    assertThat(next.status()).isEqualTo(InspectionStatus.REQUESTED);
    assertThat(next.authorSide()).isEqualTo(PartySide.TENANT);
  }

  @Test
  void INSP_1f_상대는_작성_중_7일이_지나기_전에는_INVALID_STATE() {
    InspectionState now = draft(PartySide.LANDLORD, NOW, true);
    Instant justBefore = NOW.plus(STALE).minusSeconds(1);

    assertThatThrownBy(() -> rules.apply(now, request(PartySide.TENANT, justBefore)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(rules.canRequestConfirm(now, PartySide.TENANT, justBefore)).isFalse();
  }

  @Test
  void INSP_1f_상대는_작성_중_7일이_지나면_지금까지_쓴_것으로_보낸다() {
    InspectionState now = draft(PartySide.LANDLORD, NOW, true);
    Instant stale = NOW.plus(STALE);

    InspectionState next = rules.apply(now, request(PartySide.TENANT, stale));

    assertThat(next.status()).isEqualTo(InspectionStatus.REQUESTED);
    // 누가 눌렀든 기준 측은 그대로다(요청한 측 = 기준 측, #218)
    assertThat(next.authorSide()).isEqualTo(PartySide.LANDLORD);
    assertThat(rules.canRequestConfirm(now, PartySide.TENANT, stale)).isTrue();
    assertThat(rules.counterpartCanRequestAt(now)).isEqualTo(stale);
  }

  @Test
  void 작성_중이_아니면_확인_요청을_다시_보낼_수_없다() {
    InspectionState requested =
        draft(PartySide.TENANT, NOW, true).withStatus(InspectionStatus.REQUESTED);

    assertThatThrownBy(() -> rules.apply(requested, request(PartySide.TENANT, NOW)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThatThrownBy(() -> rules.apply(InspectionState.NONE, request(PartySide.TENANT, NOW)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  @Test
  void 지금_호실_단계에서_점검_확인을_열_수_없으면_INVALID_STATE() {
    InspectionState blocked = draft(PartySide.TENANT, NOW, false);

    assertThatThrownBy(() -> rules.apply(blocked, request(PartySide.TENANT, NOW)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(rules.canRequestConfirm(blocked, PartySide.TENANT, NOW)).isFalse();
  }

  @Test
  void 재계산은_시작_시각부터_작성_중_기간을_센다() {
    Instant stale = NOW.plus(STALE);
    InspectionState folded =
        Replay.fold(
            rules,
            List.of(start(PartySide.LANDLORD, "MOVE_IN", null), request(PartySide.TENANT, stale)));

    assertThat(folded.status()).isEqualTo(InspectionStatus.REQUESTED);
    assertThat(folded.startedAt()).isEqualTo(NOW);
  }

  @Test
  void 재계산은_지금_설정으로_과거_확인_요청을_다시_거절하지_않는다() {
    // 7일일 때 8일째 받은 상대 측 확인 요청은 설정을 10일로 바꾼 뒤 다시 계산해도 확인 요청이다(#234 혜승님 리뷰 P2)
    Instant eighthDay = NOW.plus(Duration.ofDays(8));
    List<ActionEntry> history =
        List.of(start(PartySide.LANDLORD, "MOVE_IN", null), request(PartySide.TENANT, eighthDay));
    InspectionTransitions longer = new InspectionTransitions(Duration.ofDays(10));

    assertThat(Replay.fold(rules, history).status()).isEqualTo(InspectionStatus.REQUESTED);
    assertThat(Replay.fold(longer, history).status()).isEqualTo(InspectionStatus.REQUESTED);
    // 새 요청과 버튼은 지금 설정으로 본다(같은 판단 함수)
    InspectionState live = draft(PartySide.LANDLORD, NOW, true);
    assertThatThrownBy(() -> longer.apply(live, request(PartySide.TENANT, eighthDay)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(longer.canRequestConfirm(live, PartySide.TENANT, eighthDay)).isFalse();
    Instant tenthDay = NOW.plus(Duration.ofDays(10));
    assertThat(longer.canRequestConfirm(live, PartySide.TENANT, tenthDay)).isTrue();
    assertThat(longer.apply(live, request(PartySide.TENANT, tenthDay)).status())
        .isEqualTo(InspectionStatus.REQUESTED);
  }

  @Test
  void INSP_2b_입주일이_비었는데_보내지_않으면_MOVE_IN_DATE_REQUIRED() {
    assertThatThrownBy(() ->
            rules.apply(
                InspectionState.notStarted(true, false), start(PartySide.TENANT, "MOVE_IN", null)))
        .hasMessage(ErrorCode.MOVE_IN_DATE_REQUIRED.name());
  }

  @Test
  void INSP_2b_입주일이_비었으면_함께_보낸_입주일로_시작한다() {
    InspectionState next =
        rules.apply(
            InspectionState.notStarted(true, false),
            start(PartySide.TENANT, "MOVE_IN", "2026-03-01"));

    assertThat(next.status()).isEqualTo(InspectionStatus.DRAFT);
    assertThat(next.moveInDateMissing()).isFalse();
  }

  @Test
  void 입주일이_이미_있는데_보내면_VALIDATION_FAILED() {
    // 입주일을 바꾸는 것은 고치기라(03 H "늦은 입주 점검") 시작하면서 바꾸지 않는다
    assertThatThrownBy(() ->
            rules.apply(
                InspectionState.notStarted(false, false),
                start(PartySide.TENANT, "MOVE_IN", "2026-03-01")))
        .hasMessage(ErrorCode.VALIDATION_FAILED.name());
  }

  @Test
  void 현재_구현_범위의_한계로_퇴거_점검은_INVALID_STATE() {
    // 규칙이 아니라 구현 전이라서다(INSP-3, B 담당). 퇴거 점검을 만들 때 이 테스트를 바꾼다
    assertThatThrownBy(() ->
            rules.apply(
                InspectionState.notStarted(false, false),
                start(PartySide.TENANT, "MOVE_OUT", null)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  @Test
  void 거주_종료_뒤에는_입주_점검을_새로_시작할_수_없다() {
    assertThatThrownBy(() ->
            rules.apply(
                InspectionState.notStarted(false, true), start(PartySide.TENANT, "MOVE_IN", null)))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  @Test
  void 재계산은_호실_사실을_모른_채로_시작을_다시_만든다() {
    InspectionState folded =
        Replay.fold(rules, List.of(start(PartySide.TENANT, "MOVE_IN", "2026-03-01")));

    assertThat(folded.status()).isEqualTo(InspectionStatus.DRAFT);
    assertThat(folded.authorSide()).isEqualTo(PartySide.TENANT);
    assertThat(folded.moveInDateMissing()).isNull();
  }

  @Test
  void 점검_규칙은_다른_행동을_받지_않는다() {
    assertThatThrownBy(
            () -> rules.apply(InspectionState.NONE, entry(ActionType.LOCK, null, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  // ---- 항목 더하기 ----

  @Test
  void 작성_중_기준_측이_더한_항목은_확인_기다림이다() {
    InspectionItemState next =
        itemRules.apply(
            InspectionItemState.newIn(InspectionStatus.DRAFT, PartySide.TENANT),
            entry(ActionType.ADD_ITEM, PartySide.TENANT, Map.of()));

    assertThat(next.status()).isEqualTo(AckState.PENDING);
    assertThat(next.addedBy()).isEqualTo(PartySide.TENANT);
    assertThat(next.versionNo()).isEqualTo(1);
  }

  @Test
  void 현재_구현_범위의_한계로_작성_중에는_상대가_항목을_더할_수_없다() {
    assertThatThrownBy(() ->
            itemRules.apply(
                InspectionItemState.newIn(InspectionStatus.DRAFT, PartySide.TENANT),
                entry(ActionType.ADD_ITEM, PartySide.LANDLORD, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());
    assertThat(
            InspectionItemTransitions.canAdd(
                InspectionStatus.DRAFT, PartySide.TENANT, PartySide.LANDLORD))
        .isFalse();
  }

  @Test
  void 확인_요청_뒤의_항목_더하기는_아직_INVALID_STATE() {
    // 상대가 더하기·잠긴 뒤 추가는 후속 작업(INSP-1d·1e)
    for (InspectionStatus status : List.of(InspectionStatus.REQUESTED, InspectionStatus.LOCKED)) {
      assertThatThrownBy(() ->
              itemRules.apply(
                  InspectionItemState.newIn(status, PartySide.TENANT),
                  entry(ActionType.ADD_ITEM, PartySide.TENANT, Map.of())))
          .hasMessage(ErrorCode.INVALID_STATE.name());
    }
  }

  @Test
  void 이미_있는_항목에는_다시_더할_수_없다() {
    InspectionItemState existing =
        new InspectionItemState(
            InspectionStatus.DRAFT, PartySide.TENANT, AckState.PENDING, PartySide.TENANT, 1);

    assertThatThrownBy(
            () -> itemRules.apply(existing, entry(ActionType.ADD_ITEM, PartySide.TENANT, Map.of())))
        .hasMessage(ErrorCode.INVALID_STATE.name());
  }

  private static InspectionState draft(PartySide author, Instant startedAt, Boolean canOpen) {
    return new InspectionState(
        InspectionKind.MOVE_IN, InspectionStatus.DRAFT, author, startedAt, null, null, canOpen);
  }

  private static ActionEntry request(PartySide side, Instant at) {
    return entry(ActionType.REQUEST_INSPECTION_CONFIRM, side, Map.of(), at);
  }

  private static ActionEntry start(PartySide side, String kind, String moveInDate) {
    return entry(
        ActionType.START_INSPECTION,
        side,
        moveInDate == null ? Map.of("kind", kind) : Map.of("kind", kind, "moveInDate", moveInDate));
  }

  private static ActionEntry entry(ActionType type, PartySide side, Map<String, Object> payload) {
    return entry(type, side, payload, NOW);
  }

  private static ActionEntry entry(
      ActionType type, PartySide side, Map<String, Object> payload, Instant at) {
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
        null,
        payload,
        at);
  }
}
