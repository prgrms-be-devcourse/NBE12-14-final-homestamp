package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeWaitServiceTest {

  private final FakeWaitService waits = new FakeWaitService();
  private final UUID roomRecordId = UUID.randomUUID();
  private final RequestTarget repairCase =
      RequestTarget.of(TargetKind.REPAIR_CASE, UUID.randomUUID());

  @Test
  void 같은_대상_종류에_열린_요청이_있으면_새로_만들지_않는다() {
    RequestHandle first = waits.open(defectConfirm());
    RequestHandle second = waits.open(defectConfirm());

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.requestId()).isEqualTo(first.requestId());
  }

  @Test
  void 상대가_답하면_답함으로_닫힌다() {
    waits.open(defectConfirm());

    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void 미확인이어도_늦게_답하면_답함으로_닫힌다() {
    waits.open(defectConfirm());
    waits.markUnconfirmed(repairCase, RequestKind.DEFECT_CONFIRM);

    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void 닫힌_요청은_답이_와도_다시_열지_않는다() {
    waits.open(defectConfirm());
    waits.close(repairCase, RequestKind.DEFECT_CONFIRM, ClosedReason.ROOM_ENDED);

    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.CLOSED);
    assertThat(waits.closedReasonOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(ClosedReason.ROOM_ENDED);
  }

  @Test
  void 요청이_없는_기록에_답해도_아무것도_생기지_않는다() {
    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM)).isEmpty();
  }

  @Test
  void 요청을_보낸_측이_부르면_답이_아니다() {
    waits.open(defectConfirm());

    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.TENANT);

    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 종류가_다르면_다른_요청이다() {
    waits.open(defectConfirm());
    waits.open(
        new OpenRequestCommand(
            roomRecordId, RequestKind.CLOSE_CONFIRM, repairCase, PartySide.TENANT));

    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    assertThat(waits.statusOf(repairCase, RequestKind.CLOSE_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 다시_요청하면_같은_요청이_기다림으로_돌아간다() {
    RequestHandle first = waits.open(defectConfirm());
    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    RequestHandle again = waits.restart(defectConfirm());

    assertThat(again.requestId()).isEqualTo(first.requestId());
    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 요청이_없던_기록에_다시_요청하면_새로_연다() {
    RequestHandle handle = waits.restart(defectConfirm());

    assertThat(handle.created()).isTrue();
    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 그_단계에서_열_수_없는_종류는_다시_요청할_수_없다() {
    waits.blockOpening(roomRecordId, RequestKind.DEFECT_CONFIRM);

    assertThat(waits.canOpen(roomRecordId, RequestKind.DEFECT_CONFIRM)).isFalse();
    assertThat(waits.canOpen(roomRecordId, RequestKind.CORRECTION_CONFIRM)).isTrue();
    assertThatThrownBy(() -> waits.restart(defectConfirm()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 호실의_열린_요청_중_고른_종류만_한꺼번에_닫는다() {
    RequestTarget progressNote = RequestTarget.of(TargetKind.PROGRESS_NOTE, UUID.randomUUID());
    waits.open(defectConfirm());
    waits.open(
        new OpenRequestCommand(
            roomRecordId, RequestKind.SITUATION_CHECK, progressNote, PartySide.TENANT));
    waits.open(
        new OpenRequestCommand(
            roomRecordId, RequestKind.CORRECTION_CONFIRM, repairCase, PartySide.TENANT));

    int closed =
        waits.closeAll(
            roomRecordId,
            ClosedReason.ROOM_ENDED,
            Set.of(RequestKind.DEFECT_CONFIRM, RequestKind.SITUATION_CHECK));

    assertThat(closed).isEqualTo(2);
    assertThat(waits.statusOf(repairCase, RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 미확인이면_혼자_진행할_수_있다() {
    waits.open(defectConfirm());
    SoloQuery query =
        new SoloQuery(roomRecordId, RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);

    assertThat(waits.canGoSolo(query).allowed()).isFalse();

    waits.markUnconfirmed(repairCase, RequestKind.DEFECT_CONFIRM);

    assertThat(waits.canGoSolo(query)).isEqualTo(SoloCheck.allowedBecause(SoloReason.UNCONFIRMED));
  }

  private OpenRequestCommand defectConfirm() {
    return new OpenRequestCommand(
        roomRecordId, RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);
  }
}
