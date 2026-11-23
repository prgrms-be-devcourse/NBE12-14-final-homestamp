package com.chilsuitjo.back.action.rule;

import static com.chilsuitjo.back.global.common.PartySide.LANDLORD;
import static com.chilsuitjo.back.global.common.PartySide.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 응답하기·다시 요청의 공통 규칙(#86, 03 "응답과 그 뒤", 06 4-0). 세입자가 기록하고 집주인이 답하는 모양으로 본다. */
class AnswerSupportTest {

  static final Duration WEEK = Duration.ofDays(7);
  static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

  static Answerable record(AckState state) {
    return new Answerable(TENANT, state, 1, null);
  }

  static void assertError(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  @Nested
  class 응답하기 {

    @Test
    void 상대_측이_고른_값이_다음_응답_값이다() {
      assertThat(AnswerSupport.answer(record(AckState.PENDING), LANDLORD, Answer.ACK, null, 1))
          .isEqualTo(AckState.ACK);
      assertThat(
              AnswerSupport.answer(record(AckState.PENDING), LANDLORD, Answer.NEED_MORE, "사진", 1))
          .isEqualTo(AckState.NEED_MORE);
      assertThat(AnswerSupport.answer(record(AckState.PENDING), LANDLORD, Answer.DISPUTE, "아님", 1))
          .isEqualTo(AckState.DISPUTE);
    }

    @Test
    void 기록한_측은_답할_수_없다() {
      assertError(
          () -> AnswerSupport.answer(record(AckState.PENDING), TENANT, Answer.ACK, null, 1),
          ErrorCode.NOT_COUNTERPART_SIDE);
      assertError(
          () -> AnswerSupport.answer(record(AckState.PENDING), null, Answer.ACK, null, 1),
          ErrorCode.NOT_COUNTERPART_SIDE);
    }

    @Test
    void 본_판_번호가_다르면_VERSION_CONFLICT() {
      assertError(
          () -> AnswerSupport.answer(record(AckState.PENDING), LANDLORD, Answer.ACK, null, 2),
          ErrorCode.VERSION_CONFLICT);
      assertError(
          () -> AnswerSupport.answer(record(AckState.PENDING), LANDLORD, Answer.ACK, null, null),
          ErrorCode.VERSION_CONFLICT);
    }

    @Test
    void 판이_없는_기록은_번호를_보지_않는다() {
      Answerable noVersion = new Answerable(TENANT, AckState.PENDING, null, null);

      assertThat(AnswerSupport.answer(noVersion, LANDLORD, Answer.ACK, null, null))
          .isEqualTo(AckState.ACK);
    }

    @Test
    void 맞아요는_뒤집지_않는다() {
      for (Answer answer : Answer.values()) {
        assertError(
            () -> AnswerSupport.answer(record(AckState.ACK), LANDLORD, answer, "사유", 1),
            ErrorCode.ACK_FINAL);
      }
    }

    @Test
    void 보류와_이견은_사유가_있어야_한다() {
      for (String blank : new String[] {null, "", "  "}) {
        assertError(
            () ->
                AnswerSupport.answer(
                    record(AckState.PENDING), LANDLORD, Answer.NEED_MORE, blank, 1),
            ErrorCode.REASON_REQUIRED);
        assertError(
            () ->
                AnswerSupport.answer(record(AckState.PENDING), LANDLORD, Answer.DISPUTE, blank, 1),
            ErrorCode.REASON_REQUIRED);
      }
    }

    @Test
    void 보류_이견에서는_맞아요로만_바꾼다() {
      for (AckState state : new AckState[] {AckState.NEED_MORE, AckState.DISPUTE}) {
        assertThat(AnswerSupport.answer(record(state), LANDLORD, Answer.ACK, null, 1))
            .isEqualTo(AckState.ACK);
        // 질문 1 B안: 보류 ↔ 이견은 보충으로 새 요청이 온 뒤에 다시 고른다.
        assertError(
            () -> AnswerSupport.answer(record(state), LANDLORD, Answer.NEED_MORE, "사유", 1),
            ErrorCode.INVALID_STATE);
        assertError(
            () -> AnswerSupport.answer(record(state), LANDLORD, Answer.DISPUTE, "사유", 1),
            ErrorCode.INVALID_STATE);
      }
    }

    @Test
    void 확인_없음_뒤에도_답할_수_있다() {
      assertThat(
              AnswerSupport.answer(
                  record(AckState.UNCONFIRMED), LANDLORD, Answer.DISPUTE, "늦게 봤어요", 1))
          .isEqualTo(AckState.DISPUTE);
    }

    @Test
    void 고르지_않으면_VALIDATION_FAILED() {
      assertError(
          () -> AnswerSupport.answer(record(AckState.PENDING), LANDLORD, null, null, 1),
          ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void canAnswer는_answer와_같은_결과다() {
      for (AckState state : AckState.values()) {
        for (PartySide side : new PartySide[] {TENANT, LANDLORD, null}) {
          Answerable r = record(state);
          // 화면은 [맞아요] 버튼이 켜지는지를 본다. 맞아요는 어느 상태에서든 사유가 필요 없다.
          boolean succeeds = succeeds(() -> AnswerSupport.answer(r, side, Answer.ACK, null, 1));
          assertThat(AnswerSupport.canAnswer(r, side)).as(state + " " + side).isEqualTo(succeeds);
        }
      }
    }
  }

  @Nested
  class 다시_요청 {

    static Answerable asked(AckState state, Instant lastRerequestAt) {
      return new Answerable(TENANT, state, 1, lastRerequestAt);
    }

    @Test
    void 보충하면_확인_기다림으로_돌아가고_다시_요청_시각이_남는다() {
      AnswerSupport.Rerequested result =
          AnswerSupport.rerequest(asked(AckState.NEED_MORE, null), TENANT, true, true, NOW, WEEK);

      assertThat(result.state()).isEqualTo(AckState.PENDING);
      assertThat(result.rerequestedAt()).isEqualTo(NOW);
    }

    @Test
    void 기록한_측만_부른다() {
      assertError(
          () ->
              AnswerSupport.rerequest(
                  asked(AckState.DISPUTE, null), LANDLORD, true, true, NOW, WEEK),
          ErrorCode.NOT_COUNTERPART_SIDE);
    }

    @Test
    void 보류_이견일_때만() {
      for (AckState state : new AckState[] {AckState.PENDING, AckState.ACK, AckState.UNCONFIRMED}) {
        assertError(
            () -> AnswerSupport.rerequest(asked(state, null), TENANT, true, true, NOW, WEEK),
            ErrorCode.INVALID_STATE);
      }
    }

    @Test
    void 지금_열_수_없는_요청이면_INVALID_STATE() {
      // 예: 거주 종료로 닫힌 하자 확인(03 K). 판단은 notification이 하고 값은 load가 담는다.
      assertError(
          () ->
              AnswerSupport.rerequest(
                  asked(AckState.NEED_MORE, null), TENANT, true, false, NOW, WEEK),
          ErrorCode.INVALID_STATE);
    }

    @Test
    void 근거가_없으면_EVIDENCE_REQUIRED() {
      assertError(
          () ->
              AnswerSupport.rerequest(
                  asked(AckState.NEED_MORE, null), TENANT, false, true, NOW, WEEK),
          ErrorCode.EVIDENCE_REQUIRED);
    }

    @Test
    void 근거도_없고_간격_안이면_근거를_먼저_본다() {
      Instant last = NOW.minus(WEEK).plusSeconds(1);

      assertError(
          () ->
              AnswerSupport.rerequest(
                  asked(AckState.NEED_MORE, last), TENANT, false, true, NOW, WEEK),
          ErrorCode.EVIDENCE_REQUIRED);
    }

    @Test
    void 다시_요청끼리_간격_안이면_ASKED_RECENTLY() {
      Instant last = NOW.minus(WEEK).plusSeconds(1);

      assertError(
          () ->
              AnswerSupport.rerequest(
                  asked(AckState.NEED_MORE, last), TENANT, true, true, NOW, WEEK),
          ErrorCode.ASKED_RECENTLY);
      assertThat(AnswerSupport.nextRerequestAt(asked(AckState.NEED_MORE, last), WEEK))
          .isEqualTo(last.plus(WEEK));
    }

    @Test
    void 간격이_딱_지나면_된다() {
      Instant last = NOW.minus(WEEK);

      assertThatCode(() ->
              AnswerSupport.rerequest(asked(AckState.DISPUTE, last), TENANT, true, true, NOW, WEEK))
          .doesNotThrowAnyException();
    }

    @Test
    void 첫_요청_뒤에는_기다리지_않는다() {
      // 7일은 다시 요청끼리만 센다(#86). 첫 요청 직후 보류가 달려도 바로 보충할 수 있다.
      assertThat(AnswerSupport.nextRerequestAt(asked(AckState.NEED_MORE, null), WEEK)).isNull();
      assertThatCode(() ->
              AnswerSupport.rerequest(
                  asked(AckState.NEED_MORE, null), TENANT, true, true, NOW, WEEK))
          .doesNotThrowAnyException();
    }

    @Test
    void canRerequest는_근거를_더한_rerequest와_같은_결과다() {
      Instant[] lasts = {null, NOW.minus(WEEK), NOW.minusSeconds(60)};
      for (AckState state : AckState.values()) {
        for (PartySide side : new PartySide[] {TENANT, LANDLORD, null}) {
          for (boolean canOpen : new boolean[] {true, false}) {
            for (Instant last : lasts) {
              Answerable r = asked(state, last);
              boolean succeeds =
                  succeeds(() -> AnswerSupport.rerequest(r, side, true, canOpen, NOW, WEEK));
              assertThat(AnswerSupport.canRerequest(r, side, canOpen, NOW, WEEK))
                  .as(state + " " + side + " " + canOpen + " " + last)
                  .isEqualTo(succeeds);
            }
          }
        }
      }
    }
  }

  @Nested
  class 세_버튼이_함께_쓰는_검사 {

    @Test
    void 근거_필수() {
      assertError(() -> AnswerSupport.requireEvidence(false), ErrorCode.EVIDENCE_REQUIRED);
      assertThatCode(() -> AnswerSupport.requireEvidence(true)).doesNotThrowAnyException();
    }

    @Test
    void 간격에_한_번() {
      assertError(
          () -> AnswerSupport.checkInterval(NOW.minusSeconds(1), NOW, WEEK),
          ErrorCode.ASKED_RECENTLY);
      assertThatCode(() -> AnswerSupport.checkInterval(null, NOW, WEEK)).doesNotThrowAnyException();
      assertThatCode(() -> AnswerSupport.checkInterval(NOW.minus(WEEK), NOW, WEEK))
          .doesNotThrowAnyException();
    }
  }

  static boolean succeeds(ThrowingCallable call) {
    try {
      call.call();
      return true;
    } catch (BusinessException e) {
      return false;
    } catch (Throwable e) {
      throw new AssertionError(e);
    }
  }
}
