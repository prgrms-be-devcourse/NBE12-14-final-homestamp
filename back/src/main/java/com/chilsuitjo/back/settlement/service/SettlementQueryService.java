package com.chilsuitjo.back.settlement.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.settlement.dto.SettlementDetail;
import com.chilsuitjo.back.settlement.entity.Settlement;
import com.chilsuitjo.back.settlement.repository.SettlementRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 정산 조회(06 4-8, S-15). 아무것도 바꾸지 않는다. */
@Service
@RequiredArgsConstructor
public class SettlementQueryService {

  private final SettlementRepository settlements;
  private final RoomGateReader gates;
  private final WaitQuery waits;

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
    int unanswered = waits.awaiting(row.getRoomRecordId(), gate.side()).size();
    return new SettlementDetail(
        row.getId(),
        row.getRoomRecordId(),
        gate.side(),
        row.getDepositStatus(),
        row.getSnapshotNo(),
        row.getCreatedAt(),
        unanswered,
        List.of());
  }
}
