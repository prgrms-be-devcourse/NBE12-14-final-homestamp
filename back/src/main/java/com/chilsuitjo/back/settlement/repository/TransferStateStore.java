package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.AckState;
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
import com.chilsuitjo.back.settlement.entity.Settlement;
import com.chilsuitjo.back.settlement.entity.SettlementTransfer;
import com.chilsuitjo.back.settlement.entity.SettlementTransferVersion;
import com.chilsuitjo.back.settlement.rule.TransferDirection;
import com.chilsuitjo.back.settlement.rule.TransferResponse;
import com.chilsuitjo.back.settlement.rule.TransferState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산 송금 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * TransferTransitions}에만). 송금은 지불과 같은 "C 머리 + B 판"이다(05 2-7).
 */
@Component
@RequiredArgsConstructor
public class TransferStateStore {

  /** 송금 기록 payload의 정산 키. */
  public static final String SETTLEMENT_ID = "settlementId";

  /** 송금 기록 payload의 방향 키({@code TransferTransitions.RecordPayload}). */
  public static final String DIRECTION = "direction";

  /** 송금 기록 payload의 같은 송금 키({@code TransferTransitions.RecordPayload}). */
  public static final String SAME_AS = "sameAsTransferId";

  /** 내용 지문의 판 종류(05 2-3 "해시 입력 형식"). */
  static final String CONTENT_KIND = "settlement_transfer_version";

  private final SettlementRepository settlements;
  private final SettlementTransferRepository transfers;
  private final SettlementTransferVersionRepository versions;
  private final SettlementChanges changes;
  private final CounterpartReader parties;
  private final WaitService waits;
  private final ActionRequestContext requestContext;

  /** 송금 기록(06 4-8 {@code transfers}, SET-4)의 저장 방법. 금액·날짜는 첫 판에 남는다. */
  public StateStore<TransferState> recording(long amount, LocalDate paidOn) {
    return new Recording(amount, paidOn);
  }

  /** 송금 응답(06 4-8 {@code respond})의 저장 방법. */
  public StateStore<TransferState> responding() {
    return new Responding();
  }

  /** 응답이 본 판의 지문. 판 번호가 없거나 그 판이 없으면 빈 값이다(그때는 규칙이 판 번호로 거절한다). */
  public Optional<DigestInput> seenContent(UUID transferId, Integer seenVersionNo) {
    if (seenVersionNo == null) {
      return Optional.empty();
    }
    return versions
        .findBySettlementTransferIdAndNo(transferId, seenVersionNo)
        .map(TransferStateStore::digest);
  }

  /** 이 송금 행의 지금 상태. 상세 조회의 {@code availableActions}도 같은 값을 쓴다(06 4-0 "서버의 같은 함수"). */
  public TransferState current(SettlementTransfer row) {
    SettlementTransferVersion version =
        versions
            .findById(row.getCurrentVersionId())
            .orElseThrow(() -> new IllegalStateException("송금의 지금 판이 없어요: " + row.getId()));
    return new TransferState(
        row.getDirection(),
        row.getRecordedBySide(),
        AckState.valueOf(row.getResponse().name()),
        version.getNo(),
        null);
  }

  public static DigestInput digest(SettlementTransferVersion version) {
    return new DigestInput(
        CONTENT_KIND,
        version.getId(),
        version.getNo(),
        List.of(
            new DigestField("amount", Long.toString(version.getAmount())),
            new DigestField("paid_on", version.getPaidOn().toString())));
  }

  private TransferState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target == null) {
      return TransferState.beforeRecord(sameAsOk(command));
    }
    if (target.kind() != TargetKind.SETTLEMENT_TRANSFER) {
      throw new IllegalArgumentException("송금이 아닌 대상이에요: " + target);
    }
    return current(row(command));
  }

  /**
   * 받은 쪽이 묶으려는 송금이 같은 정산·같은 방향의 보낸 쪽 기록인지(06 4-8 "서버는 같은 정산·같은 방향인지 확인"). 묶지 않으면 null, 다른 호실·없는
   * 송금이면 거짓이다.
   */
  private Boolean sameAsOk(ActionCommand command) {
    Object sameAs = command.payload().get(SAME_AS);
    if (sameAs == null) {
      return null;
    }
    UUID settlementId = settlementId(command);
    TransferDirection direction =
        TransferDirection.valueOf(command.payload().get(DIRECTION).toString());
    return transfers
        .findByIdAndRoomRecordId(UUID.fromString(sameAs.toString()), command.roomRecordId())
        .filter(t -> t.getSettlementId().equals(settlementId))
        .filter(t -> t.getDirection() == direction)
        .filter(t -> t.getRecordedBySide() == TransferState.sender(direction))
        .isPresent();
  }

  private SettlementTransfer row(ActionCommand command) {
    return transfers
        .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static UUID settlementId(ActionCommand command) {
    return UUID.fromString(Objects.requireNonNull(command.payload().get(SETTLEMENT_ID)).toString());
  }

  /**
   * 상대에게 수령 확인 요청을 열 수 있나. 확인과 합의는 연결한 사람끼리라 상대가 연결됐을 때만 연다(연결 전·상대 없음이면 요청 없이 혼자 기록, 03 1-4 "연결 전
   * 상대" ②). 공유 중단 제한이 살아 있으면 열지 않는다(03 B, 불변식 18). 거주 종료 뒤에도 연다(03 K, {@code RequestOpening}).
   */
  private boolean canAsk(UUID roomRecordId, PartySide by) {
    RoomParties room = parties.read(roomRecordId, by);
    return room.counterpart().presence() == PartyPresence.CONNECTED
        && !room.mySharingStoppedApplies()
        && !room.counterpartSharingStoppedApplies()
        && room.canOpen(RequestKind.RECEIPT_CONFIRM)
        && waits.canOpen(roomRecordId, RequestKind.RECEIPT_CONFIRM);
  }

  private final class Recording implements StateStore<TransferState> {

    private final long amount;
    private final LocalDate paidOn;

    private Recording(long amount, LocalDate paidOn) {
      this.amount = amount;
      this.paidOn = paidOn;
    }

    @Override
    public TransferState load(ActionCommand command) {
      return TransferStateStore.this.load(command);
    }

    /**
     * 송금 머리와 첫 판을 만들고 정산 모습 번호를 올린다. 상대가 연결됐으면 그 상대에게 확인 요청({@code RECEIPT_CONFIRM}, 요청한 측 = 기록한
     * 측)을 연다. 내용 지문은 첫 판이다.
     */
    @Override
    public SavedState save(ActionCommand command, TransferState next) {
      UUID roomRecordId = command.roomRecordId();
      UUID settlementId = settlementId(command);
      Settlement settlement =
          settlements
              .findById(settlementId)
              .filter(s -> s.getRoomRecordId().equals(roomRecordId))
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
      Object sameAs = command.payload().get(SAME_AS);
      Instant at = requestContext.at();
      UUID transferId = Uuids.v7();
      UUID versionId = Uuids.v7();
      transfers.save(
          new SettlementTransfer(
              transferId,
              roomRecordId,
              settlement.getId(),
              next.direction(),
              versionId,
              next.recordedBy(),
              sameAs == null ? null : UUID.fromString(sameAs.toString()),
              at));
      SettlementTransferVersion first =
          new SettlementTransferVersion(
              versionId, roomRecordId, transferId, 1, amount, paidOn, null, at);
      versions.save(first);
      transfers.flush();
      changes.changed(settlement.getId());
      if (canAsk(roomRecordId, next.recordedBy())) {
        waits.open(
            new OpenRequestCommand(
                roomRecordId,
                RequestKind.RECEIPT_CONFIRM,
                RequestTarget.of(TargetKind.SETTLEMENT_TRANSFER, transferId),
                next.recordedBy()));
      }
      return new SavedState(
          ActionTarget.of(TargetKind.SETTLEMENT_TRANSFER, transferId), digest(first));
    }
  }

  private final class Responding implements StateStore<TransferState> {

    @Override
    public TransferState load(ActionCommand command) {
      return TransferStateStore.this.load(command);
    }

    /**
     * 응답을 남기고(맞아요면 지금 판에 도장) 정산 모습 번호를 올린 뒤 그 송금의 수령 확인 요청을 답함으로 닫는다(보류·이견도 답, 03 G). 내용 지문은 서비스가
     * 넘긴 본 판이다.
     */
    @Override
    public SavedState save(ActionCommand command, TransferState next) {
      SettlementTransfer row = row(command);
      row.answer(TransferResponse.valueOf(next.response().name()), requestContext.at());
      transfers.flush();
      changes.changed(row.getSettlementId());
      waits.answered(
          RequestTarget.of(TargetKind.SETTLEMENT_TRANSFER, row.getId()),
          RequestKind.RECEIPT_CONFIRM,
          command.actor().side());
      return SavedState.unchanged();
    }
  }
}
