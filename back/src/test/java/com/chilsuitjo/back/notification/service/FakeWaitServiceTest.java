package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeWaitServiceTest {

  /** 바로 혼자 진행이 읽는 자리 사실. 테스트마다 바꾼다(기본: 상대가 연결됨, 공유 중). */
  private RoomParties parties = parties(connected());

  private final FakeWaitService waits = new FakeWaitService((room, side) -> parties);
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
  void 답한_요청에_다시_요청하면_새_요청을_연다() {
    RequestHandle first = waits.open(defectConfirm());
    waits.answered(repairCase, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    RequestHandle again = waits.restart(defectConfirm());

    assertThat(again.created()).isTrue();
    assertThat(again.requestId()).isNotEqualTo(first.requestId());
    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 닫힌_요청은_다시_요청해도_되살리지_않고_새로_연다() {
    RequestHandle first = waits.open(defectConfirm());
    waits.close(repairCase, RequestKind.DEFECT_CONFIRM, ClosedReason.SHARING_STOPPED);

    RequestHandle again = waits.restart(defectConfirm());

    assertThat(again.created()).isTrue();
    assertThat(again.requestId()).isNotEqualTo(first.requestId());
    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.WAITING);
    assertThat(waits.closedReasonOf(repairCase, RequestKind.DEFECT_CONFIRM)).isEmpty();
  }

  @Test
  void 기다리는_요청에_다시_요청하면_같은_요청에_묶는다() {
    RequestHandle first = waits.open(defectConfirm());

    RequestHandle again = waits.restart(defectConfirm());

    assertThat(again.created()).isFalse();
    assertThat(again.requestId()).isEqualTo(first.requestId());
    assertThat(waits.statusOf(repairCase, RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 미확인인_점검_확인에_다시_요청해도_미확인과_혼자_진행이_그대로다() {
    RequestTarget inspection = RequestTarget.of(TargetKind.INSPECTION, UUID.randomUUID());
    OpenRequestCommand confirm =
        new OpenRequestCommand(
            roomRecordId, RequestKind.INSPECTION_CONFIRM, inspection, PartySide.TENANT);
    SoloQuery solo =
        new SoloQuery(roomRecordId, RequestKind.INSPECTION_CONFIRM, inspection, PartySide.TENANT);
    RequestHandle first = waits.open(confirm);
    waits.markUnconfirmed(inspection, RequestKind.INSPECTION_CONFIRM, PartySide.TENANT);

    RequestHandle again = waits.restart(confirm);

    assertThat(again.created()).isFalse();
    assertThat(again.requestId()).isEqualTo(first.requestId());
    assertThat(waits.statusOf(inspection, RequestKind.INSPECTION_CONFIRM, PartySide.TENANT))
        .contains(RequestStatus.UNCONFIRMED);
    assertThat(waits.canGoSolo(solo)).isEqualTo(SoloCheck.allowedBecause(SoloReason.UNCONFIRMED));
  }

  // ---- 점검 확인은 점검 × 요청한 측마다 하나(03 H, V1 ux_inspection_confirm_waiting) ----

  @Test
  void 점검_확인은_요청한_측마다_따로_연다() {
    RequestHandle fromTenant = waits.open(inspectionConfirm(PartySide.TENANT));
    RequestHandle fromLandlord = waits.open(inspectionConfirm(PartySide.LANDLORD));

    assertThat(fromTenant.created()).isTrue();
    assertThat(fromLandlord.created()).isTrue();
    assertThat(fromLandlord.requestId()).isNotEqualTo(fromTenant.requestId());
  }

  @Test
  void 점검_확인에_답하면_답한_측에게_온_요청만_닫힌다() {
    waits.open(inspectionConfirm(PartySide.TENANT));
    waits.open(inspectionConfirm(PartySide.LANDLORD));

    // 세입자가 집주인이 적은 항목에 모두 답함 = 집주인이 보낸 요청에 답함
    waits.answered(inspection, RequestKind.INSPECTION_CONFIRM, PartySide.TENANT);

    assertThat(waits.statusOf(inspection, RequestKind.INSPECTION_CONFIRM, PartySide.LANDLORD))
        .contains(RequestStatus.ANSWERED);
    assertThat(waits.statusOf(inspection, RequestKind.INSPECTION_CONFIRM, PartySide.TENANT))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 점검_확인을_닫으면_두_방향_모두_닫힌다() {
    waits.open(inspectionConfirm(PartySide.TENANT));
    waits.open(inspectionConfirm(PartySide.LANDLORD));

    waits.close(inspection, RequestKind.INSPECTION_CONFIRM, ClosedReason.SHARING_STOPPED);

    for (PartySide side : PartySide.values()) {
      assertThat(waits.statusOf(inspection, RequestKind.INSPECTION_CONFIRM, side))
          .contains(RequestStatus.CLOSED);
      assertThat(waits.closedReasonOf(inspection, RequestKind.INSPECTION_CONFIRM, side))
          .contains(ClosedReason.SHARING_STOPPED);
    }
  }

  @Test
  void 점검_확인의_혼자_진행은_요청한_측마다_따로_본다() {
    waits.open(inspectionConfirm(PartySide.TENANT));
    waits.open(inspectionConfirm(PartySide.LANDLORD));

    waits.markUnconfirmed(inspection, RequestKind.INSPECTION_CONFIRM, PartySide.TENANT);

    assertThat(waits.canGoSolo(inspectionSolo(PartySide.TENANT)).allowed()).isTrue();
    assertThat(waits.canGoSolo(inspectionSolo(PartySide.LANDLORD)).allowed()).isFalse();
  }

  @Test
  void 방향_없이_점검_확인을_물으면_거절한다() {
    waits.open(inspectionConfirm(PartySide.TENANT));

    assertThatThrownBy(() -> waits.statusOf(inspection, RequestKind.INSPECTION_CONFIRM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> waits.markUnconfirmed(inspection, RequestKind.INSPECTION_CONFIRM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- 제안은 안건마다 기다리는 요청 하나(V1 ux_proposal_waiting). 역제안은 05 proposal ③ ----

  @Test
  void 역제안이면_이전_제안_요청은_답함이고_새_요청을_연다() {
    RequestHandle proposal = waits.open(proposal(PartySide.LANDLORD));

    // 역제안 = 이전 안에 대한 이견 + 새 안. 05 ③ "이전 안의 요청은 ANSWERED, 다른 안에 새 요청"
    waits.answered(agenda, RequestKind.PROPOSAL, PartySide.TENANT);
    assertThat(waits.statusOf(agenda, RequestKind.PROPOSAL)).contains(RequestStatus.ANSWERED);
    RequestHandle counter = waits.open(proposal(PartySide.TENANT));

    assertThat(counter.created()).isTrue();
    assertThat(counter.requestId()).isNotEqualTo(proposal.requestId());
    assertThat(waits.statusOf(agenda, RequestKind.PROPOSAL)).contains(RequestStatus.WAITING);
  }

  @Test
  void 역제안_뒤_처음_낸_쪽이_답하면_역제안_요청이_닫힌다() {
    waits.open(proposal(PartySide.LANDLORD));
    waits.answered(agenda, RequestKind.PROPOSAL, PartySide.TENANT);
    waits.open(proposal(PartySide.TENANT));

    waits.answered(agenda, RequestKind.PROPOSAL, PartySide.LANDLORD);

    // 역제안 요청은 세입자가 보냈으니 집주인의 답으로 닫힌다(기다림에는 끝이 있다)
    assertThat(waits.statusOf(agenda, RequestKind.PROPOSAL)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 이전_제안_요청을_닫지_않고_상대가_열면_거절한다() {
    waits.open(proposal(PartySide.LANDLORD));

    // answered 없이 open만 부르면 이전 요청이 남아 역제안 기다림이 끝나지 않는다
    assertThatThrownBy(() -> waits.open(proposal(PartySide.TENANT)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 같은_쪽이_다시_열면_같은_제안_요청이다() {
    RequestHandle first = waits.open(proposal(PartySide.LANDLORD));

    RequestHandle again = waits.open(proposal(PartySide.LANDLORD));

    assertThat(again.created()).isFalse();
    assertThat(again.requestId()).isEqualTo(first.requestId());
  }

  @Test
  void 제안을_바꾸면_확인_없음이던_요청이_다시_기다림이다() {
    RequestHandle first = waits.open(proposal(PartySide.LANDLORD));
    waits.markUnconfirmed(agenda, RequestKind.PROPOSAL);

    // 05 proposal ② "미확인이었으면 WAITING으로 돌아감", 03 E "낸 쪽이 바꾸면 같은 요청이 다시 기다림"
    RequestHandle changed = waits.restart(proposal(PartySide.LANDLORD));

    assertThat(changed.created()).isFalse();
    assertThat(changed.requestId()).isEqualTo(first.requestId());
    assertThat(waits.statusOf(agenda, RequestKind.PROPOSAL)).contains(RequestStatus.WAITING);
  }

  @Test
  void 상대_쪽_제안_요청이_열려_있으면_바꾸기로_다시_요청할_수_없다() {
    waits.open(proposal(PartySide.LANDLORD));

    // 상대 안에 대한 새 안은 바꾸기(②)가 아니라 역제안(③): answered 다음 open
    assertThatThrownBy(() -> waits.restart(proposal(PartySide.TENANT)))
        .isInstanceOf(IllegalStateException.class);
  }

  private final RequestTarget agenda = RequestTarget.of(TargetKind.REPAIR_CASE, UUID.randomUUID());

  private OpenRequestCommand proposal(PartySide requestedBySide) {
    return new OpenRequestCommand(roomRecordId, RequestKind.PROPOSAL, agenda, requestedBySide);
  }

  private final RequestTarget inspection =
      RequestTarget.of(TargetKind.INSPECTION, UUID.randomUUID());

  private OpenRequestCommand inspectionConfirm(PartySide requestedBySide) {
    return new OpenRequestCommand(
        roomRecordId, RequestKind.INSPECTION_CONFIRM, inspection, requestedBySide);
  }

  private SoloQuery inspectionSolo(PartySide requestedBySide) {
    return new SoloQuery(roomRecordId, RequestKind.INSPECTION_CONFIRM, inspection, requestedBySide);
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

  @Test
  void 바로_혼자_진행은_자리_사실로_판단한다() {
    parties = parties(PartyFacts.empty());

    assertThat(waits.canGoSoloDirectly(closeDirectly()))
        .isEqualTo(SoloCheck.allowedBecause(SoloReason.NO_COUNTERPART));
  }

  @Test
  void 바로_혼자_진행은_정해_둔_결과와_메모리_미확인을_보지_않는다() {
    // 가짜는 운영에서도 쓰이는 동안 테스트 도구로 만든 근거로 혼자 진행을 열지 않는다
    waits.seedSolo(
        repairCase, RequestKind.CLOSE_CONFIRM, SoloCheck.allowedBecause(SoloReason.OPTED_OUT));
    waits.open(defectConfirm());
    waits.markUnconfirmed(repairCase, RequestKind.DEFECT_CONFIRM);

    assertThat(waits.canGoSoloDirectly(closeDirectly())).isEqualTo(SoloCheck.notYet(null));
    assertThat(
            waits.canGoSolo(
                new SoloQuery(
                    roomRecordId, RequestKind.CLOSE_CONFIRM, repairCase, PartySide.TENANT)))
        .isEqualTo(SoloCheck.allowedBecause(SoloReason.OPTED_OUT));
  }

  @Test
  void 바로_혼자_진행은_행동하는_측으로_자리를_읽는다() {
    PartySide[] asked = new PartySide[1];
    FakeWaitService reading =
        new FakeWaitService((room, side) -> {
          asked[0] = side;
          return parties;
        });

    reading.canGoSoloDirectly(
        new SoloQuery(roomRecordId, RequestKind.CLOSE_CONFIRM, repairCase, PartySide.LANDLORD));

    assertThat(asked[0]).isEqualTo(PartySide.LANDLORD);
  }

  @Test
  void 자리를_읽지_못하면_불가로_바꾸지_않고_예외를_그대로_던진다() {
    FakeWaitService missing =
        new FakeWaitService((room, side) -> {
          throw new BusinessException(ErrorCode.NOT_FOUND);
        });

    assertThatThrownBy(() -> missing.canGoSoloDirectly(closeDirectly()))
        .isInstanceOf(BusinessException.class);
  }

  private SoloQuery closeDirectly() {
    return new SoloQuery(roomRecordId, RequestKind.CLOSE_CONFIRM, repairCase, PartySide.TENANT);
  }

  private static PartyFacts connected() {
    return new PartyFacts(
        UUID.randomUUID(), UUID.randomUUID(), PartyPresence.CONNECTED, null, true, false, null);
  }

  private static RoomParties parties(PartyFacts counterpart) {
    return new RoomParties(RoomPhase.LIVING, connected(), counterpart);
  }

  private OpenRequestCommand defectConfirm() {
    return new OpenRequestCommand(
        roomRecordId, RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT);
  }
}
