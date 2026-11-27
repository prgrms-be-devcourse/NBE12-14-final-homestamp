package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 가짜 응답 요청의 읽기 전용 조회(#266). 쓰기(WaitService)와 같은 메모리 상태를 읽는지 본다. */
class FakeWaitServiceWaitQueryTest {

  private final MovingClock clock = new MovingClock(Instant.parse("2026-11-26T00:00:00Z"));
  private final FakeWaitService waits = new FakeWaitService((room, side) -> null, clock);
  private final UUID room = UUID.randomUUID();
  private final RequestTarget repairCase = target(TargetKind.REPAIR_CASE);

  @Test
  void awaiting은_답할_측에게_온_열린_요청만_준다() {
    // 세입자가 요청 → 집주인이 답할 것
    open(RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);
    // 집주인이 요청 → 세입자가 답할 것
    RequestTarget other = target(TargetKind.REPAIR_CASE);
    open(RequestKind.CLOSE_CONFIRM, other, PartySide.LANDLORD);

    assertThat(waits.awaiting(room, PartySide.LANDLORD))
        .singleElement()
        .satisfies(view -> {
          assertThat(view.kind()).isEqualTo(RequestKind.DEFECT_CONFIRM);
          assertThat(view.target()).isEqualTo(repairCase);
          assertThat(view.requestedBySide()).isEqualTo(PartySide.TENANT);
          assertThat(view.answeringSide()).isEqualTo(PartySide.LANDLORD);
          assertThat(view.status()).isEqualTo(RequestStatus.WAITING);
        });
    assertThat(waits.awaiting(room, PartySide.TENANT))
        .extracting(RequestView::kind)
        .containsExactly(RequestKind.CLOSE_CONFIRM);
  }

  @Test
  void awaiting은_미확인도_열린_요청으로_준다() {
    open(RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);
    waits.markUnconfirmed(repairCase, RequestKind.DEFECT_CONFIRM);

    assertThat(waits.awaiting(room, PartySide.LANDLORD))
        .extracting(RequestView::status)
        .containsExactly(RequestStatus.UNCONFIRMED);
  }

  @Test
  void awaiting은_답함_닫힘_거둠을_빼고_latest는_닫힌_요청도_준다() {
    RequestTarget answered = target(TargetKind.REPAIR_CASE);
    RequestTarget withdrawn = target(TargetKind.REPAIR_CASE);
    open(RequestKind.DEFECT_CONFIRM, answered, PartySide.TENANT);
    open(RequestKind.CLOSE_CONFIRM, withdrawn, PartySide.TENANT);
    open(RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);
    waits.answered(answered, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);
    waits.withdrawn(withdrawn, RequestKind.CLOSE_CONFIRM, PartySide.TENANT);
    clock.advance(Duration.ofMinutes(5));
    waits.close(repairCase, RequestKind.DEFECT_CONFIRM, ClosedReason.ROOM_ENDED);

    assertThat(waits.awaiting(room, PartySide.LANDLORD)).isEmpty();
    assertThat(waits.latest(RequestKind.DEFECT_CONFIRM, answered))
        .map(RequestView::status)
        .contains(RequestStatus.ANSWERED);
    assertThat(waits.latest(RequestKind.CLOSE_CONFIRM, withdrawn))
        .map(RequestView::status)
        .contains(RequestStatus.WITHDRAWN);
    RequestView closed = waits.latest(RequestKind.DEFECT_CONFIRM, repairCase).orElseThrow();
    assertThat(closed.status()).isEqualTo(RequestStatus.CLOSED);
    assertThat(closed.closedReason()).isEqualTo(ClosedReason.ROOM_ENDED);
    assertThat(closed.closedAt()).isEqualTo(clock.instant());
  }

  @Test
  void 답함은_닫힘이_아니라_닫힌_이유와_시각이_없다() {
    open(RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);
    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    RequestView view = waits.latest(RequestKind.DEFECT_CONFIRM, repairCase).orElseThrow();
    assertThat(view.closedReason()).isNull();
    assertThat(view.closedAt()).isNull();
  }

  @Test
  void 다른_호실의_요청은_awaiting에_없다() {
    waits.open(
        new OpenRequestCommand(
            UUID.randomUUID(), RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT));

    assertThat(waits.awaiting(room, PartySide.LANDLORD)).isEmpty();
  }

  @Test
  void latest는_대상과_종류가_같은_요청만_읽는다() {
    open(RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);

    assertThat(waits.latest(RequestKind.CLOSE_CONFIRM, repairCase)).isEmpty();
    assertThat(waits.latest(RequestKind.DEFECT_CONFIRM, target(TargetKind.REPAIR_CASE))).isEmpty();
  }

  @Test
  void 닫힌_뒤_다시_열면_latest는_새_요청이다() {
    open(RequestKind.CLOSE_CONFIRM, repairCase, PartySide.TENANT);
    waits.answered(repairCase, RequestKind.CLOSE_CONFIRM, PartySide.LANDLORD);
    clock.advance(Duration.ofHours(1));
    open(RequestKind.CLOSE_CONFIRM, repairCase, PartySide.LANDLORD);

    RequestView latest = waits.latest(RequestKind.CLOSE_CONFIRM, repairCase).orElseThrow();
    assertThat(latest.status()).isEqualTo(RequestStatus.WAITING);
    assertThat(latest.requestedBySide()).isEqualTo(PartySide.LANDLORD);
    assertThat(latest.openedAt()).isEqualTo(clock.instant());
  }

  @Test
  void latest는_점검_확인을_받지_않는다() {
    assertThatThrownBy(
            () -> waits.latest(RequestKind.INSPECTION_CONFIRM, target(TargetKind.INSPECTION)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 점검_확인도_awaiting에는_방향대로_나온다() {
    RequestTarget inspection = target(TargetKind.INSPECTION);
    open(RequestKind.INSPECTION_CONFIRM, inspection, PartySide.TENANT);
    open(RequestKind.INSPECTION_CONFIRM, inspection, PartySide.LANDLORD);

    assertThat(waits.awaiting(room, PartySide.LANDLORD))
        .extracting(RequestView::requestedBySide)
        .containsExactly(PartySide.TENANT);
  }

  @Test
  void restart로_묶어도_openedAt은_처음_연_시각이다() {
    Instant openedAt = clock.instant();
    RequestTarget correction = target(TargetKind.REPAIR_CASE);
    open(RequestKind.CORRECTION_CONFIRM, correction, PartySide.TENANT);
    clock.advance(Duration.ofDays(1));

    waits.restart(
        new OpenRequestCommand(room, RequestKind.CORRECTION_CONFIRM, correction, PartySide.TENANT));

    assertThat(waits.latest(RequestKind.CORRECTION_CONFIRM, correction))
        .map(RequestView::openedAt)
        .contains(openedAt);
  }

  @Test
  void 가짜는_통지가_없어_첫_전달_기한_혼자_진행_이유를_만들지_않는다() {
    open(RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);
    waits.markUnconfirmed(repairCase, RequestKind.DEFECT_CONFIRM);

    RequestView view = waits.latest(RequestKind.DEFECT_CONFIRM, repairCase).orElseThrow();
    assertThat(view.firstNotifiedAt()).isNull();
    assertThat(view.dueAt()).isNull();
    assertThat(view.soloReason()).isNull();
  }

  private void open(RequestKind kind, RequestTarget target, PartySide side) {
    waits.open(new OpenRequestCommand(room, kind, target, side));
  }

  private static RequestTarget target(TargetKind kind) {
    return RequestTarget.of(kind, UUID.randomUUID());
  }

  private static final class MovingClock extends Clock {
    private Instant now;

    MovingClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
