package com.chilsuitjo.back.settlement.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.CorrectionReader;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.settlement.dto.SettlementDetail;
import com.chilsuitjo.back.settlement.entity.Settlement;
import com.chilsuitjo.back.settlement.entity.SettlementTransfer;
import com.chilsuitjo.back.settlement.entity.SettlementTransferVersion;
import com.chilsuitjo.back.settlement.repository.SettlementRepository;
import com.chilsuitjo.back.settlement.repository.SettlementStateStore;
import com.chilsuitjo.back.settlement.repository.SettlementTransferRepository;
import com.chilsuitjo.back.settlement.repository.SettlementTransferVersionRepository;
import com.chilsuitjo.back.settlement.repository.TransferStateStore;
import com.chilsuitjo.back.settlement.rule.SettlementState;
import com.chilsuitjo.back.settlement.rule.SettlementTransitions;
import com.chilsuitjo.back.settlement.rule.TransferDirection;
import com.chilsuitjo.back.settlement.rule.TransferResponse;
import com.chilsuitjo.back.settlement.rule.TransferTransitions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 정산 조회(06 4-8, S-15). 아무것도 바꾸지 않는다. */
@Service
@RequiredArgsConstructor
public class SettlementQueryService {

  private final SettlementRepository settlements;
  private final SettlementTransferRepository transfers;
  private final SettlementTransferVersionRepository versions;
  private final TransferStateStore transferStore;
  private final RoomGateReader gates;
  private final CounterpartReader parties;
  private final WaitQuery waits;
  private final CorrectionReader correctionReader;
  private final SettlementStateStore settlementStore;
  private final SettlementTransitions rules;
  private final Clock clock;

  /**
   * 정산 화면(06 4-8 {@code GET /api/settlements/{id}}). 없는 정산·당사자가 아니면 {@code NOT_FOUND}다(ROOM-2a).
   */
  @Transactional(readOnly = true)
  public SettlementDetail detail(UUID settlementId, UUID memberId) {
    UUID roomRecordId =
        settlements
            .findRoomRecordIdById(settlementId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    Settlement row =
        settlements
            .findById(settlementId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return detailOf(row, gate);
  }

  /**
   * 호실의 정산(06 4-8 {@code GET /api/rooms/{id}/settlement}). 화면이 호실에서 정산을 찾을 때와 {@code
   * SETTLEMENT_EXISTS} 뒤 "그쪽으로 열게요"(4-10)에 쓴다. 당사자가 아니거나 아직 열지 않았으면 {@code NOT_FOUND}다.
   */
  @Transactional(readOnly = true)
  public SettlementDetail ofRoom(UUID roomRecordId, UUID memberId) {
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    Settlement row =
        settlements
            .findByRoomRecordId(roomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return detailOf(row, gate);
  }

  private SettlementDetail detailOf(Settlement row, RoomGate gate) {
    PartySide viewer = gate.side();
    int unanswered = waits.awaiting(row.getRoomRecordId(), viewer).size();
    Instant cutoff = sharingCutoff(parties.read(row.getRoomRecordId(), viewer));
    List<SettlementTransfer> shown =
        transfers.findBySettlementIdOrderByCreatedAtAscIdAsc(row.getId()).stream()
            .filter(t -> visible(t, viewer, cutoff))
            .toList();
    Map<UUID, SettlementTransferVersion> byId = versionsOf(shown);
    boolean canAnswer = gate.rejection(ActionType.RESPOND_TRANSFER).isEmpty();
    boolean canCorrect = gate.rejection(ActionType.CORRECT).isEmpty();
    List<SettlementDetail.Transfer> views = new ArrayList<>();
    for (SettlementTransfer t : shown) {
      views.add(view(t, byId, viewer, cutoff, canAnswer, canCorrect));
    }
    List<String> actions = new ArrayList<>();
    if (gate.rejection(ActionType.RECORD_TRANSFER).isEmpty()) {
      actions.add("transfers");
    }
    // 끝내기 버튼은 실제 요청과 같은 판단이다(06 4-0): 호실 단위 거절은 RoomGate, 정산 규칙은 SettlementTransitions
    SettlementState state = settlementStore.current(row, viewer);
    if (gate.rejection(ActionType.PROPOSE_FINISH).isEmpty()
        && rules.proposeBlocked(state, viewer, clock.instant()) == null) {
      actions.add("finish");
    }
    if (gate.rejection(ActionType.CONFIRM_FINISH).isEmpty()
        && SettlementTransitions.canAnswer(state, viewer)) {
      actions.add("finish-confirm");
      actions.add("finish-not-yet");
    }
    if (gate.rejection(ActionType.FINISH_ALONE).isEmpty()
        && SettlementTransitions.aloneBlocked(state, viewer) == null) {
      actions.add("finish-alone");
    }
    return new SettlementDetail(
        row.getId(),
        row.getRoomRecordId(),
        viewer,
        row.getDepositStatus(),
        row.getSnapshotNo(),
        row.getCreatedAt(),
        state.settled(),
        state.finishRequestedBy(),
        unanswered,
        List.copyOf(views),
        confirmedTotal(views, TransferDirection.REFUND),
        confirmedTotal(views, TransferDirection.EXTRA),
        List.copyOf(actions));
  }

  private SettlementDetail.Transfer view(
      SettlementTransfer t,
      Map<UUID, SettlementTransferVersion> byId,
      PartySide viewer,
      Instant cutoff,
      boolean canAnswer,
      boolean canCorrect) {
    SettlementTransferVersion current = byId.get(t.getCurrentVersionId());
    // 공유를 그만둔 시각 뒤 상대가 남긴 맞아요는 보이지 않는다(불변식 18)
    boolean confirmShown =
        t.getConfirmedVersionId() != null
            && !(t.getRecordedBySide() == viewer
                && cutoff != null
                && t.getReceiptConfirmedAt().isAfter(cutoff));
    SettlementTransferVersion confirmed = confirmShown ? byId.get(t.getConfirmedVersionId()) : null;
    AckState response =
        confirmShown || t.getResponse() != TransferResponse.ACK
            ? AckState.valueOf(t.getResponse().name())
            : AckState.PENDING;
    List<String> actions = new ArrayList<>();
    if (canAnswer && TransferTransitions.canRespond(transferStore.current(t), viewer)) {
      actions.add("respond");
    }
    if (canCorrect && t.getRecordedBySide() == viewer) {
      actions.add("corrections");
    }
    // 고친 내용: 내가 고친 것은 늘, 상대가 고친 것은 공유를 그만둔 시각까지 보인다(불변식 18)
    List<CorrectionView> pending =
        correctionReader.findByRecord(CorrectionTargetKind.SETTLEMENT_TRANSFER, t.getId()).stream()
            .filter(c ->
                c.requestedBySide() == viewer || cutoff == null || !c.createdAt().isAfter(cutoff))
            .toList();
    return new SettlementDetail.Transfer(
        t.getId(),
        t.getDirection(),
        t.getRecordedBySide(),
        current.getAmount(),
        current.getPaidOn(),
        current.getNo(),
        response,
        confirmed == null ? null : confirmed.getAmount(),
        t.getSameAsTransferId(),
        confirmShown ? t.getReceiptConfirmedAt() : null,
        t.getCreatedAt(),
        pending,
        List.copyOf(actions));
  }

  /**
   * 송금 합계: 상대가 확인한 판만 더한다. 같은 송금으로 묶인 기록(보낸 쪽 기록과 받은 쪽의 [받았어요(혼자)])은 한 번만 세고, 둘 다 확인됐으면 받은 쪽이 적은 실제
   * 받은 금액을 쓴다(03 J, 06 SET-4e).
   */
  static long confirmedTotal(List<SettlementDetail.Transfer> views, TransferDirection direction) {
    Map<UUID, List<SettlementDetail.Transfer>> groups = new LinkedHashMap<>();
    for (SettlementDetail.Transfer t : views) {
      if (t.direction() != direction || t.confirmedAmount() == null) {
        continue;
      }
      UUID root = t.sameAsTransferId() == null ? t.id() : t.sameAsTransferId();
      groups.computeIfAbsent(root, key -> new ArrayList<>()).add(t);
    }
    long total = 0;
    for (List<SettlementDetail.Transfer> group : groups.values()) {
      Optional<SettlementDetail.Transfer> received =
          group.stream()
              .filter(t -> t.sameAsTransferId() != null)
              .max(Comparator.comparing(SettlementDetail.Transfer::createdAt));
      total += received.orElse(group.get(0)).confirmedAmount();
    }
    return total;
  }

  private Map<UUID, SettlementTransferVersion> versionsOf(List<SettlementTransfer> shown) {
    Set<UUID> ids = new HashSet<>();
    for (SettlementTransfer t : shown) {
      ids.add(t.getCurrentVersionId());
      if (t.getConfirmedVersionId() != null) {
        ids.add(t.getConfirmedVersionId());
      }
    }
    return versions.findAllById(ids).stream()
        .collect(Collectors.toMap(SettlementTransferVersion::getId, Function.identity()));
  }

  /** 공유를 그만둔 시각 뒤 상대가 남긴 송금은 보이지 않는다(불변식 18). 내 기록은 늘 보인다. */
  private static boolean visible(SettlementTransfer t, PartySide viewer, Instant cutoff) {
    return t.getRecordedBySide() == viewer || cutoff == null || !t.getCreatedAt().isAfter(cutoff);
  }

  /**
   * 보이는 범위를 고정하는 시각(03 6-6 9번, 불변식 18). 제한이 살아 있는 공유 중단 시각 중 이른 것이고, 없으면 null이다(점검의 {@code
   * InspectionSight.sharingCutoff}와 같은 기준).
   */
  private static Instant sharingCutoff(RoomParties room) {
    Instant cutoff = null;
    if (room.mySharingStoppedApplies()) {
      cutoff = room.mine().sharingStoppedAt();
    }
    if (room.counterpartSharingStoppedApplies()) {
      Instant other = room.counterpart().sharingStoppedAt();
      cutoff = cutoff == null || other.isBefore(cutoff) ? other : cutoff;
    }
    return cutoff;
  }
}
