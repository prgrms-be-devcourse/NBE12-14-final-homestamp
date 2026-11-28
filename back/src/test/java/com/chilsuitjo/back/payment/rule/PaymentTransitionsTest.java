package com.chilsuitjo.back.payment.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 지불 규칙(06 MONEY-1a·1c·2a). 순수 함수라 DB 없이 본다. */
class PaymentTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
  private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 27);

  private final PaymentTransitions rules = new PaymentTransitions();

  @ParameterizedTest
  @EnumSource(
      value = PaymentResponse.class,
      names = {"PENDING", "NEED_MORE", "DISPUTE"})
  void 상대의_확인_전_기록이_있으면_그_기록으로_안내한다(PaymentResponse response) {
    UUID existing = UUID.randomUUID();
    PaymentState now = recording(similar(existing, PartySide.LANDLORD, response, PAID_ON));

    assertDuplicate(() -> rules.apply(now, record(PartySide.TENANT, false)), existing, false);
  }

  @Test
  void 상대의_확인_전_기록은_그래도_남기기로도_기간이_지나도_넘길_수_없다() {
    UUID existing = UUID.randomUUID();
    PaymentState now =
        recording(
            similar(existing, PartySide.LANDLORD, PaymentResponse.PENDING, PAID_ON.minusDays(90)));

    assertDuplicate(() -> rules.apply(now, record(PartySide.TENANT, true)), existing, false);
  }

  @Test
  void 확인_전_기록과_비슷한_기록이_함께_있으면_확인_전_기록으로_안내한다() {
    UUID mine = UUID.randomUUID();
    UUID theirs = UUID.randomUUID();
    PaymentState now =
        recording(
            similar(mine, PartySide.TENANT, PaymentResponse.PENDING, PAID_ON),
            similar(theirs, PartySide.LANDLORD, PaymentResponse.PENDING, PAID_ON));

    assertDuplicate(() -> rules.apply(now, record(PartySide.TENANT, false)), theirs, false);
  }

  @Test
  void 내_기록이_30일_안에_있으면_묻는다() {
    UUID existing = UUID.randomUUID();
    PaymentState now =
        recording(similar(existing, PartySide.TENANT, PaymentResponse.PENDING, PAID_ON));

    assertDuplicate(() -> rules.apply(now, record(PartySide.TENANT, false)), existing, true);
  }

  @Test
  void 상대_기록을_내가_맞다고_했어도_30일_안이면_묻는다() {
    UUID existing = UUID.randomUUID();
    PaymentState now =
        recording(similar(existing, PartySide.LANDLORD, PaymentResponse.ACK, PAID_ON));

    assertDuplicate(() -> rules.apply(now, record(PartySide.TENANT, false)), existing, true);
  }

  @ParameterizedTest
  @ValueSource(ints = {-30, 30})
  void 낸_날짜가_앞뒤_30일이면_묻는다(int days) {
    UUID existing = UUID.randomUUID();
    PaymentState now =
        recording(similar(existing, PartySide.TENANT, PaymentResponse.ACK, PAID_ON.plusDays(days)));

    assertDuplicate(() -> rules.apply(now, record(PartySide.TENANT, false)), existing, true);
  }

  @ParameterizedTest
  @ValueSource(ints = {-31, 31})
  void 낸_날짜가_31일_넘게_떨어지면_남긴다(int days) {
    PaymentState now =
        recording(
            similar(
                UUID.randomUUID(), PartySide.TENANT, PaymentResponse.ACK, PAID_ON.plusDays(days)));

    assertThat(rules.apply(now, record(PartySide.TENANT, false))).isEqualTo(now);
  }

  @Test
  void 비슷한_기록이_있어도_그래도_남기면_남긴다() {
    PaymentState now =
        recording(similar(UUID.randomUUID(), PartySide.TENANT, PaymentResponse.PENDING, PAID_ON));

    assertThat(rules.apply(now, record(PartySide.TENANT, true))).isEqualTo(now);
  }

  @Test
  void 같은_지불이_없으면_남긴다() {
    PaymentState now = recording();

    assertThat(rules.apply(now, record(PartySide.TENANT, false))).isEqualTo(now);
  }

  @Test
  void 기록한_측은_답할_수_없다() {
    PaymentState now = PaymentState.forResponding(PartySide.TENANT, PaymentResponse.PENDING, 1);

    assertCode(
        () -> rules.apply(now, respond(PartySide.TENANT, Answer.ACK, null, 1)),
        ErrorCode.NOT_COUNTERPART_SIDE);
  }

  @Test
  void 본_판이_지금_판이_아니면_답할_수_없다() {
    PaymentState now = PaymentState.forResponding(PartySide.TENANT, PaymentResponse.PENDING, 2);

    assertCode(
        () -> rules.apply(now, respond(PartySide.LANDLORD, Answer.ACK, null, 1)),
        ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void 상대가_맞아요나_사유와_함께_이견을_남긴다() {
    PaymentState now = PaymentState.forResponding(PartySide.TENANT, PaymentResponse.PENDING, 1);

    assertThat(rules.apply(now, respond(PartySide.LANDLORD, Answer.ACK, null, 1)).response())
        .isEqualTo(PaymentResponse.ACK);
    assertThat(
            rules
                .apply(now, respond(PartySide.LANDLORD, Answer.DISPUTE, "8만 원이 아니에요", 1))
                .response())
        .isEqualTo(PaymentResponse.DISPUTE);
  }

  @Test
  void 이견에는_사유가_있어야_한다() {
    PaymentState now = PaymentState.forResponding(PartySide.TENANT, PaymentResponse.PENDING, 1);

    assertCode(
        () -> rules.apply(now, respond(PartySide.LANDLORD, Answer.DISPUTE, " ", 1)),
        ErrorCode.REASON_REQUIRED);
  }

  @Test
  void 맞아요_뒤에는_다시_답할_수_없다() {
    PaymentState now = PaymentState.forResponding(PartySide.TENANT, PaymentResponse.ACK, 1);

    assertCode(
        () -> rules.apply(now, respond(PartySide.LANDLORD, Answer.DISPUTE, "다시 보니 달라요", 1)),
        ErrorCode.ACK_FINAL);
  }

  @Test
  void 측이_없거나_다른_행동이면_INVALID_STATE() {
    assertCode(() -> rules.apply(recording(), record(null, false)), ErrorCode.INVALID_STATE);
    assertCode(
        () -> rules.apply(PaymentState.NONE, respond(PartySide.LANDLORD, Answer.ACK, null, 1)),
        ErrorCode.INVALID_STATE);
    assertCode(
        () -> rules.apply(recording(), entry(ActionType.SUPPLEMENT_PAYMENT, PartySide.TENANT)),
        ErrorCode.INVALID_STATE);
  }

  private static PaymentState recording(SimilarPayment... similar) {
    return PaymentState.forRecording(PAID_ON, List.of(similar));
  }

  private static SimilarPayment similar(
      UUID id, PartySide recordedBy, PaymentResponse response, LocalDate paidOn) {
    return new SimilarPayment(id, recordedBy, response, paidOn, NOW);
  }

  private static ActionEntry record(PartySide side, boolean similarConfirmed) {
    Map<String, Object> payload =
        similarConfirmed
            ? Map.of(PaymentTransitions.RecordPayload.SIMILAR_CONFIRMED, true)
            : Map.of();
    return new ActionEntry(
        null,
        UUID.randomUUID(),
        0,
        ActionType.RECORD_PAYMENT,
        side,
        "k",
        null,
        null,
        null,
        null,
        null,
        null,
        payload,
        NOW);
  }

  private static ActionEntry respond(
      PartySide side, Answer answer, String reason, Integer seenVersionNo) {
    return new ActionEntry(
        null,
        UUID.randomUUID(),
        0,
        ActionType.RESPOND_AMOUNT,
        side,
        "k",
        null,
        null,
        null,
        seenVersionNo,
        null,
        reason,
        Map.of("answer", answer.name()),
        NOW);
  }

  private static ActionEntry entry(ActionType type, PartySide side) {
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
        Map.of(),
        NOW);
  }

  private static void assertDuplicate(Runnable call, UUID existing, boolean similar) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.DUPLICATE_PAYMENT);
              assertThat(e.duplicate())
                  .contains(new BusinessException.Duplicate(existing, similar));
            });
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }
}
