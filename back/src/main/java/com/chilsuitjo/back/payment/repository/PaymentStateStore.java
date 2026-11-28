package com.chilsuitjo.back.payment.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionVisibility;
import com.chilsuitjo.back.action.rule.ActionVisibility.Cutoff;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.payment.dto.PaymentCreateRequest;
import com.chilsuitjo.back.payment.entity.Payment;
import com.chilsuitjo.back.payment.entity.PaymentVersion;
import com.chilsuitjo.back.payment.rule.PaymentAmountConfirmed;
import com.chilsuitjo.back.payment.rule.PaymentResponse;
import com.chilsuitjo.back.payment.rule.PaymentState;
import com.chilsuitjo.back.payment.service.RepairPhotoScope;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 지불을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * PaymentTransitions}에만).
 *
 * <p>머리({@code state.payment}, C)와 판({@code record.payment_version}, B)을 한 트랜잭션에 만든다. 판의 머리 FK는 바로
 * 검사하므로 머리를 먼저 DB에 내보낸다(머리의 지금 판 FK는 지연). 기록 행동의 대상은 새 지불이고 내용 지문은 첫 판이다. 응답은 머리의 응답·같이 확인한 판만 바꾸고
 * 판은 그대로다(05 2-5, 06 MONEY-2a).
 *
 * <p>공유를 그만둔 뒤 상대가 남겨 보이지 않는 지불은 중복 후보에서 빼고 응답할 수 없다(03 3-1, 불변식 18). 보이는 범위는 수리 건 상세({@code
 * RepairSight.sees})와 같은 기준이다. payment는 repair를 모르므로 {@code ActionVisibility.Cutoff}로 본다.
 */
@Component
@RequiredArgsConstructor
public class  PaymentStateStore {

  /** 내용 지문의 판 종류(05 2-3 「해시 입력 형식」 내용 지문). */
  static final String CONTENT_KIND = "payment_version";

  private final PaymentRepository payments;
  private final PaymentVersionRepository versions;
  private final CounterpartReader parties;
  private final ActionRequestContext requestContext;
  private final RoomAccessService access;
  private final PhotoService photos;
  private final RepairPhotoScope repairPhotos;
  private final WaitService waits;
  private final ApplicationEventPublisher events;

  /**
   * 기록(06 MONEY-1)의 저장 방법. 칸 검증은 서비스가 pipeline 전에 끝낸다. 같은 키로 다시 온 요청이면 pipeline이 처음 만든 지불을 결과로
   * 돌려준다.
   */
  public StateStore<PaymentState> recording(UUID repairCaseId, PaymentCreateRequest request) {
    Objects.requireNonNull(request, "request");
    return new Recording(
        Objects.requireNonNull(repairCaseId, "repairCaseId"),
        request,
        request.uploadTickets() == null ? List.of() : List.copyOf(request.uploadTickets()));
  }

  /** 응답(맞아요·보류·이견)의 저장 방법. */
  public StateStore<PaymentState> responding() {
    return new Responding();
  }

  /**
   * 응답할 때 본 판의 내용 지문 재료(본 기록에 대고 하는 행동, 안내서 3-3). 판은 고칠 수 없어서 잠그기 전에 읽어도 같다. 본 판 번호가 없거나 그런 판이 없으면
   * 비어 있다. 판 번호가 다르면 {@code apply}가 {@code VERSION_CONFLICT}로 막는다.
   */
  public Optional<DigestInput> seenContent(UUID paymentId, Integer seenVersionNo) {
    if (seenVersionNo == null) {
      return Optional.empty();
    }
    return versions.findByPaymentIdAndNo(paymentId, seenVersionNo).map(PaymentStateStore::digest);
  }

  /** 지불의 호실. 없는 지불이면 {@code NOT_FOUND}. */
  public UUID roomOf(UUID paymentId) {
    return payments
        .findById(paymentId)
        .map(Payment::getRoomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /** 판의 내용 지문 재료. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3). 기록한 측은 행동 기록에 있어 넣지 않는다. */
  static DigestInput digest(PaymentVersion version) {
    return new DigestInput(
        CONTENT_KIND,
        version.getId(),
        version.getNo(),
        List.of(
            new DigestField("payer_kind", version.getPayerKind().name()),
            new DigestField("payer_label", version.getPayerLabel()),
            new DigestField("payee_kind", version.getPayeeKind().name()),
            new DigestField("payee_label", version.getPayeeLabel()),
            new DigestField("amount", Long.toString(version.getAmount())),
            new DigestField("paid_on", version.getPaidOn().toString()),
            new DigestField("evidence_kind", version.getEvidenceKind()),
            new DigestField(
                "previous_id",
                version.getPreviousId() == null ? null : version.getPreviousId().toString())));
  }

  /**
   * 이 측이 이 시각에 남긴 지불이 보는 사람에게 보이나. 내 기록은 늘 보인다. 상대 기록은 제한이 살아 있는 공유 중단 시각({@code shared}) 뒤면 보이지
   * 않고, 상대가 탈퇴했으면 남아 있는 공유 중단 시각({@code departed}) 뒤에 그 측이 남긴 것도 보이지 않는다({@code RepairSight.sees}와
   * 같음).
   */
  static boolean sees(Cutoff cutoff, PartySide viewer, PartySide side, Instant at) {
    if (side == viewer) {
      return true;
    }
    if (cutoff.shared() != null && at.isAfter(cutoff.shared())) {
      return false;
    }
    return cutoff.departed() == null || !at.isAfter(cutoff.departed());
  }

  private Cutoff cutoff(UUID roomRecordId, PartySide viewer) {
    RoomParties room = parties.read(roomRecordId, viewer);
    return ActionVisibility.cutoff(
        room.mine().sharingStoppedAt(),
        room.counterpart().sharingStoppedAt(),
        room.counterpart().withdrawn());
  }

  /**
   * 남긴 지불에 상대의 금액 확인 요청을 연다(03 F "금액 확인", 06 LINK-1e). 상대가 <b>연결된 회원</b>일 때만 연다: 초대만 했거나(연결 전) 자리가
   * 비었으면 돈은 요청 없이 혼자 기록한다(03 1-4 "연결 전 상대" ②, 불변식 20). 링크로 알리는 하자 확인 요청과 다르다. 공유 중단 제한이 살아 있거나 호실
   * 단계에서 열 수 없어도 열지 않는다. 통지·문자는 만들지 않는다.
   */
  private void openAmountConfirm(UUID roomRecordId, UUID paymentId, PartySide side) {
    RoomParties room = parties.read(roomRecordId, side);
    if (room.counterpart().presence() != PartyPresence.CONNECTED
        || room.mySharingStoppedApplies()
        || room.counterpartSharingStoppedApplies()
        || !room.canOpen(RequestKind.AMOUNT_CONFIRM)
        || !waits.canOpen(roomRecordId, RequestKind.AMOUNT_CONFIRM)) {
      return;
    }
    waits.open(
        new OpenRequestCommand(
            roomRecordId,
            RequestKind.AMOUNT_CONFIRM,
            RequestTarget.of(TargetKind.PAYMENT, paymentId),
            side));
  }

  private final class Recording implements StateStore<PaymentState> {

    private final UUID repairCaseId;
    private final PaymentCreateRequest request;
    private final List<String> uploadTickets;

    private Recording(UUID repairCaseId, PaymentCreateRequest request, List<String> uploadTickets) {
      this.repairCaseId = repairCaseId;
      this.request = request;
      this.uploadTickets = uploadTickets;
    }

    /** 같은 방향·금액의 지불 중 행동하는 사람에게 보이는 것. 측이 없으면 빈 상태다({@code apply}가 {@code INVALID_STATE}). */
    @Override
    public PaymentState load(ActionCommand command) {
      PartySide side = command.actor().side();
      if (side == null) {
        return PaymentState.NONE;
      }
      Cutoff cutoff = cutoff(command.roomRecordId(), side);
      return PaymentState.forRecording(
          request.paidOn(),
          payments
              .findSameDirectionAndAmount(
                  command.roomRecordId(),
                  repairCaseId,
                  request.payer().kind(),
                  request.payee().kind(),
                  request.amount())
              .stream()
              .filter(
                  candidate -> sees(cutoff, side, candidate.recordedBy(), candidate.createdAt()))
              .toList());
    }

    /**
     * 머리와 첫 판을 만들고 증빙 사진을 그 지불에 붙인다. 시각은 행동 기록과 같은 {@code at()}이다. 업로드 표가 있으면 증빙 있음으로 시작하고, 사진이
     * 실패하면 지불도 함께 되돌려진다.
     */
    @Override
    public SavedState save(ActionCommand command, PaymentState next) {
      UUID paymentId = Uuids.v7();
      UUID versionId = Uuids.v7();
      Instant at = requestContext.at();
      payments.saveAndFlush(
          new Payment(
              paymentId,
              command.roomRecordId(),
              repairCaseId,
              versionId,
              !uploadTickets.isEmpty(),
              at));
      PaymentVersion version =
          versions.saveAndFlush(
              new PaymentVersion(
                  versionId,
                  command.roomRecordId(),
                  paymentId,
                  1,
                  request.payer().kind(),
                  request.payer().label(),
                  request.payee().kind(),
                  request.payee().label(),
                  request.amount(),
                  request.paidOn(),
                  command.actor().side(),
                  request.evidenceKind(),
                  null,
                  at));
      openAmountConfirm(command.roomRecordId(), paymentId, command.actor().side());
      return new SavedState(ActionTarget.payment(paymentId), digest(version))
          .withPhotos(attach(command, paymentId));
    }

    private List<PhotoDigest> attach(ActionCommand command, UUID paymentId) {
      if (uploadTickets.isEmpty()) {
        return List.of();
      }
      return photos.attach(
          new PhotoAttachCommand(
              access.require(command.roomRecordId(), command.actor().memberId()),
              new PhotoTarget.Ref(PhotoTargetType.PAYMENT, paymentId),
              PhotoStage.EVIDENCE,
              uploadTickets,
              repairPhotos.quota(command.roomRecordId(), repairCaseId),
              requestContext.at()));
    }
  }

  private final class Responding implements StateStore<PaymentState> {

    /**
     * 답할 지불의 지금 상태. 다른 호실의 지불, 공유를 그만둔 뒤 상대가 남겨 보이지 않는 지불은 {@code NOT_FOUND}다(있는지도 알 수 없게,
     * ROOM-2a).
     */
    @Override
    public PaymentState load(ActionCommand command) {
      ActionTarget target = command.target();
      if (target == null || target.kind() != TargetKind.PAYMENT) {
        throw new IllegalArgumentException("지불이 아닌 대상이에요: " + target);
      }
      Payment payment =
          payments
              .findByIdAndRoomRecordId(target.id(), command.roomRecordId())
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
      PaymentVersion current =
          versions
              .findById(payment.getCurrentVersionId())
              .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + payment.getId()));
      PartySide side = command.actor().side();
      if (side != null
          && !sees(
              cutoff(command.roomRecordId(), side),
              side,
              current.getRecordedBySide(),
              payment.getCreatedAt())) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
      return PaymentState.forResponding(
          current.getRecordedBySide(), payment.getResponse(), current.getNo());
    }

    /**
     * 맞아요면 지금 판이 같이 확인한 판이 되고, 보류·이견이면 응답만 바뀐다. 답과 사유는 행동 기록에 남는다. 어느 답이든 이 지불의 금액 확인 요청을 닫는다(열린
     * 요청만, {@code WaitService.answered}). 맞아요면 지불을 DB에 내보낸 뒤 {@code PaymentAmountConfirmed}를 낸다. 같은
     * 트랜잭션 안의 동기 이벤트라 듣는 쪽은 {@code confirmedTotal}로 새 값을 읽는다.
     */
    @Override
    public SavedState save(ActionCommand command, PaymentState next) {
      Payment payment =
          payments
              .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
      boolean confirmed = next.response() == PaymentResponse.ACK;
      if (confirmed) {
        payment.confirmCurrent();
      } else {
        payment.changeResponse(next.response());
      }
      payments.saveAndFlush(payment);
      waits.answered(
          RequestTarget.of(TargetKind.PAYMENT, payment.getId()),
          RequestKind.AMOUNT_CONFIRM,
          command.actor().side());
      if (confirmed) {
        events.publishEvent(
            new PaymentAmountConfirmed(
                payment.getRoomRecordId(), payment.getRepairCaseId(), payment.getId()));
      }
      return SavedState.unchanged();
    }
  }
}
