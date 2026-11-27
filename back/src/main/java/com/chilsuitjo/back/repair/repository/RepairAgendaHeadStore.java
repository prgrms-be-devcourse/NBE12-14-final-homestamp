package com.chilsuitjo.back.repair.repository;

import static com.chilsuitjo.back.global.error.ErrorCode.NOT_FOUND;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.payment.service.PaymentService;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaState;
import com.chilsuitjo.back.proposal.rule.AgendaStatus;
import com.chilsuitjo.back.proposal.service.AgendaHeadStore;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.rule.MoneyStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수리비 부담 안건의 head({@code repair_case}의 {@code money_status}·{@code current_proposal_id}·{@code
 * agreed_proposal_id})를 읽고 쓴다({@code AgendaHeadStore}, decisions 2026-11-24). 규칙은 검사하지 않는다(규칙은
 * {@code ProposalTransitions}에만).
 *
 * <p>기준 금액({@code requiredTotal})은 {@code repair_case}에 없어서 payment에서 읽어 채운다({@code repair →
 * payment}, 09 3장).
 */
@Component
@RequiredArgsConstructor
public class RepairAgendaHeadStore implements AgendaHeadStore {

  private final RepairCaseRepository repairCaseRepository;
  private final PaymentService paymentService;

  @Override
  public AgendaKind kind() {
    return AgendaKind.REPAIR_BURDEN;
  }

  @Override
  public AgendaState load(UUID roomRecordId, UUID targetId) {
    RepairCase repairCase =
        repairCaseRepository
            .findByIdAndRoomRecordId(targetId, roomRecordId)
            .orElseThrow(() -> new BusinessException(NOT_FOUND));
    long requiredTotal = paymentService.confirmedTotal(roomRecordId, targetId);
    return new AgendaState(
        roomRecordId,
        new AgendaRef(AgendaKind.REPAIR_BURDEN, targetId),
        toAgendaStatus(repairCase.getMoneyStatus()),
        repairCase.getCurrentProposalId(),
        repairCase.getAgreedProposalId(),
        requiredTotal);
  }

  /**
   * {@inheritDoc}
   *
   * <p>같은 트랜잭션에서 {@link #load}가 읽은 행이라 {@code findById}로 1차 캐시에서 꺼낸다(SQL 없음). 호실 확인은 {@code load}가
   * 이미 했으므로 여기서 어긋나면 코드 버그다. 관리 중인 엔티티라 {@code save()}를 부르지 않아도 commit 때 dirty checking이 UPDATE를
   * 보낸다. {@code requiredTotal}은 읽기 전용 값이라 쓰지 않는다.
   */
  @Override
  public void save(AgendaState next) {
    if (next.agenda().kind() != AgendaKind.REPAIR_BURDEN) {
      throw new IllegalArgumentException("수리비 부담 안건이 아니에요: " + next.agenda());
    }
    UUID targetId = next.agenda().targetId();
    UUID roomRecordId = next.roomRecordId();
    RepairCase repairCase =
        repairCaseRepository
            .findById(targetId)
            .filter(row -> row.getRoomRecordId().equals(roomRecordId))
            .orElseThrow(() ->
                new IllegalStateException("이 호실의 수리 건이 아니에요: " + roomRecordId + "/" + targetId));
    repairCase.moveMoneyHead(
        toMoneyStatus(next.status()), next.currentProposalId(), next.agreedProposalId());
  }

  /** {@code MoneyStatus} → {@code AgendaStatus}. 표는 {@link AgendaStatus} 주석과 같다. */
  private static AgendaStatus toAgendaStatus(MoneyStatus status) {
    return switch (status) {
      case UNDECIDED -> AgendaStatus.NONE;
      case PENDING -> AgendaStatus.PENDING;
      case AGREED -> AgendaStatus.AGREED;
      case NO_COST -> AgendaStatus.NO_COST;
      case DISPUTE -> AgendaStatus.DISPUTE;
    };
  }

  /** {@code AgendaStatus} → {@code MoneyStatus}. {@code EXCLUDED}는 정산 항목에만 있어 거절한다. */
  public static MoneyStatus toMoneyStatus(AgendaStatus status) {
    return switch (status) {
      case NONE -> MoneyStatus.UNDECIDED;
      case PENDING -> MoneyStatus.PENDING;
      case AGREED -> MoneyStatus.AGREED;
      case NO_COST -> MoneyStatus.NO_COST;
      case DISPUTE -> MoneyStatus.DISPUTE;
      case EXCLUDED -> throw new IllegalArgumentException("수리비 부담에는 EXCLUDED가 없어요");
    };
  }
}
