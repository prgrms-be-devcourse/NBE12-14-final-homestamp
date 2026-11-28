package com.chilsuitjo.back.settlement.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 정산 송금 규칙(03 J, 06 SET-4)을 순수 함수로 본다. */
class TransferTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");

  private final TransferTransitions rules = new TransferTransitions();

  @Test
  void 보내는_측이_기록하면_보낸_기록이고_확인_기다림이다() {
    TransferState next = rules.apply(rules.initial(), record(PartySide.LANDLORD, "REFUND", null));

    assertThat(next.recordedBy()).isEqualTo(PartySide.LANDLORD);
    assertThat(next.direction()).isEqualTo(TransferDirection.REFUND);
    assertThat(next.response()).isEqualTo(AckState.PENDING);
    assertThat(next.versionNo()).isEqualTo(1);
  }

  @Test
  void 받는_측이_기록하면_받았어요이고_같은_송금으로_묶을_수_있다() {
    TransferState next =
        rules.apply(
            TransferState.beforeRecord(true),
            record(PartySide.TENANT, "REFUND", UUID.randomUUID()));

    assertThat(next.recordedBy()).isEqualTo(PartySide.TENANT);
    // 추가 송금은 세입자가 보내므로 집주인이 받는 측이다
    assertThat(TransferState.sender(TransferDirection.EXTRA)).isEqualTo(PartySide.TENANT);
  }

  @Test
  void 보낸_쪽이_묶거나_묶을_수_없는_기록에_묶으면_VALIDATION_FAILED() {
    assertCode(
        () ->
            rules.apply(
                TransferState.beforeRecord(true),
                record(PartySide.LANDLORD, "REFUND", UUID.randomUUID())),
        ErrorCode.VALIDATION_FAILED);
    assertCode(
        () ->
            rules.apply(
                TransferState.beforeRecord(false),
                record(PartySide.TENANT, "REFUND", UUID.randomUUID())),
        ErrorCode.VALIDATION_FAILED);
    assertCode(
        () -> rules.apply(rules.initial(), record(PartySide.TENANT, null, null)),
        ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void 이미_있는_송금을_다시_기록하면_INVALID_STATE() {
    assertCode(
        () -> rules.apply(recorded(), record(PartySide.LANDLORD, "REFUND", null)),
        ErrorCode.INVALID_STATE);
  }

  @Test
  void 기록하지_않은_측이_답하고_맞아요는_되돌릴_수_없다() {
    TransferState acked = rules.apply(recorded(), respond(PartySide.TENANT, "ACK", null, 1));

    assertThat(acked.response()).isEqualTo(AckState.ACK);
    assertCode(
        () -> rules.apply(acked, respond(PartySide.TENANT, "DISPUTE", "다름", 1)),
        ErrorCode.ACK_FINAL);
    assertCode(
        () -> rules.apply(recorded(), respond(PartySide.LANDLORD, "ACK", null, 1)),
        ErrorCode.NOT_COUNTERPART_SIDE);
    assertCode(
        () -> rules.apply(recorded(), respond(PartySide.TENANT, "ACK", null, 2)),
        ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void 없는_송금에_답하면_NOT_FOUND이고_다른_행동은_INVALID_STATE() {
    assertCode(
        () -> rules.apply(rules.initial(), respond(PartySide.TENANT, "ACK", null, 1)),
        ErrorCode.NOT_FOUND);
    assertCode(
        () -> rules.apply(recorded(), entry(ActionType.REGISTER, PartySide.TENANT, Map.of(), null)),
        ErrorCode.INVALID_STATE);
  }

  @Test
  void 버튼은_답할_수_있는_측에만_켜진다() {
    assertThat(TransferTransitions.canRespond(recorded(), PartySide.TENANT)).isTrue();
    assertThat(TransferTransitions.canRespond(recorded(), PartySide.LANDLORD)).isFalse();
    assertThat(TransferTransitions.canRespond(rules.initial(), PartySide.TENANT)).isFalse();
    assertThat(TransferTransitions.CHANGES)
        .contains(ActionType.RECORD_TRANSFER, ActionType.RESPOND_TRANSFER);
  }

  private TransferState recorded() {
    return rules.apply(rules.initial(), record(PartySide.LANDLORD, "REFUND", null));
  }

  private static ActionEntry record(PartySide side, String direction, UUID sameAs) {
    Map<String, Object> payload = new HashMap<>();
    if (direction != null) {
      payload.put("direction", direction);
    }
    if (sameAs != null) {
      payload.put("sameAsTransferId", sameAs.toString());
    }
    return entry(ActionType.RECORD_TRANSFER, side, payload, null);
  }

  private static ActionEntry respond(PartySide side, String answer, String reason, int seen) {
    return new ActionEntry(
        null,
        UUID.randomUUID(),
        0,
        ActionType.RESPOND_TRANSFER,
        side,
        "k",
        null,
        null,
        null,
        seen,
        null,
        reason,
        Map.of("answer", answer),
        NOW);
  }

  private static ActionEntry entry(
      ActionType type, PartySide side, Map<String, Object> payload, Integer seen) {
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
        null,
        payload,
        NOW);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }
}
