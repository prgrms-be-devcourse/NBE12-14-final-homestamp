package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.correction.service.CorrectionCloser;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.rule.SoloReason;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.room.service.RoomSettlement;
import com.chilsuitjo.back.settlement.entity.Settlement;
import com.chilsuitjo.back.settlement.rule.SettlementState;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산 상태 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * SettlementTransitions}에만). 끝내기 요청의 상태는 응답 요청({@code SETTLE_FINISH}, 대상 = 정산)이 원본이다.
 */
@Component
@RequiredArgsConstructor
public class SettlementStateStore {

  /** 혼자 끝내기 행동 payload의 혼자 진행 이유 키(05 {@code action} payload {@code solo_reason}). */
  public static final String SOLO_REASON = "solo_reason";

  private final SettlementRepository settlements;
  private final ActionRequestContext requestContext;
  private final CounterpartReader parties;
  private final WaitService waits;
  private final WaitQuery waitQuery;
  private final ActionReader actions;
  private final RoomSettlement roomSettlement;
  private final CorrectionCloser corrections;

  /** 정산 열기(06 4-8, SET-1)의 저장 방법. 퇴거 점검 시작이 같은 요청에서 열 때도 이것을 쓴다(#320). */
  public StateStore<SettlementState> opening() {
    return new Opening();
  }

  /** 정산 끝내기 제안(06 4-8 {@code finish})의 저장 방법. */
  public StateStore<SettlementState> proposing() {
    return new Finishing(Step.PROPOSE);
  }

  /** 정산 끝내기 확인(06 4-8 {@code finish/confirm})의 저장 방법. */
  public StateStore<SettlementState> confirming() {
    return new Finishing(Step.CONFIRM);
  }

  /** [아직 안 끝났어요](06 4-8 {@code finish/not-yet})의 저장 방법. */
  public StateStore<SettlementState> notYet() {
    return new Finishing(Step.NOT_YET);
  }

  /** 혼자 끝내기(06 4-8 {@code finish/alone})의 저장 방법. */
  public StateStore<SettlementState> alone() {
    return new Finishing(Step.ALONE);
  }

  /**
   * 이 정산의 지금 상태를 이 측에서 본 값({@link Finishing#load}와 같은 값). 상세 조회가 {@code availableActions}를 계산할 때도
   * 쓴다(06 4-0 "서버의 같은 함수").
   */
  public SettlementState current(Settlement row, PartySide side) {
    UUID roomRecordId = row.getRoomRecordId();
    RoomParties room = parties.read(roomRecordId, side);
    RequestTarget target = target(row.getId());
    PartySide requestedBy = openRequest(target).map(RequestView::requestedBySide).orElse(null);
    SettlementState.Facts facts =
        new SettlementState.Facts(
            room.phase() != RoomPhase.LIVING,
            row.getSnapshotNo(),
            room.counterpart().presence() == PartyPresence.CONNECTED,
            room.mySharingStoppedApplies() || room.counterpartSharingStoppedApplies(),
            lastNotYetAt(row.getId(), side),
            soloReason(roomRecordId, target, side, requestedBy));
    return new SettlementState(true, requestedBy, room.phase() == RoomPhase.SETTLED, facts);
  }

  /** 이 정산의 열린(기다림·미확인) 끝내기 요청. */
  Optional<RequestView> openRequest(RequestTarget target) {
    return waitQuery
        .latest(RequestKind.SETTLE_FINISH, target)
        .filter(
            r -> r.status() == RequestStatus.WAITING || r.status() == RequestStatus.UNCONFIRMED);
  }

  static RequestTarget target(UUID settlementId) {
    return RequestTarget.of(TargetKind.SETTLEMENT, settlementId);
  }

  /** 이 측이 보낸 끝내기 요청에 상대가 [아직 안 끝났어요]로 답한 마지막 시각(행동 기록이 원본). */
  private Instant lastNotYetAt(UUID settlementId, PartySide side) {
    Instant last = null;
    for (ActionEntry entry : actions.findByTarget(ActionTarget.settlement(settlementId))) {
      if (entry.type() == ActionType.NOT_YET_FINISH
          && entry.actorSide() != side
          && (last == null || entry.createdAt().isAfter(last))) {
        last = entry.createdAt();
      }
    }
    return last;
  }

  /**
   * 지금 혼자 끝낼 수 있는 이유(03 1-1, 06 SET-5d). 내가 보낸 요청이 열려 있으면 그 요청 기준(72시간 뒤 미확인 등, {@code canGoSolo}),
   * 요청이 없으면 요청 없이 바로({@code canGoSoloDirectly}: 상대 없음 등). 상대가 보낸 요청이 열려 있으면 혼자 끝내지 않고 답한다.
   */
  private SoloReason soloReason(
      UUID roomRecordId, RequestTarget target, PartySide side, PartySide requestedBy) {
    SoloQuery query = new SoloQuery(roomRecordId, RequestKind.SETTLE_FINISH, target, side);
    SoloCheck check;
    if (requestedBy == side) {
      check = waits.canGoSolo(query);
    } else if (requestedBy == null) {
      check = waits.canGoSoloDirectly(query);
    } else {
      return null;
    }
    return check.allowed() ? check.reason() : null;
  }

  private Settlement row(ActionCommand command) {
    ActionTarget target = Objects.requireNonNull(command.target(), "target");
    if (target.kind() != TargetKind.SETTLEMENT) {
      throw new IllegalArgumentException("정산이 아닌 대상이에요: " + target);
    }
    return settlements
        .findById(target.id())
        .filter(s -> s.getRoomRecordId().equals(command.roomRecordId()))
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /**
   * 정산 완료(SET-5a·5d·5f). 호실을 정산 완료로 바꾸고(삭제 기한 저장), 남은 기다림을 모두 "정산 완료로 닫힘"으로 닫는다: 응답 요청 전부와 확인 전 고친
   * 내용. 닫힌 요청에는 재통지가 나가지 않는다.
   */
  private void settle(UUID roomRecordId) {
    roomSettlement.settle(roomRecordId, requestContext.at());
    waits.closeAll(roomRecordId, ClosedReason.SETTLED, EnumSet.allOf(RequestKind.class));
    corrections.closeAllInRoom(roomRecordId, ClosedReason.SETTLED);
  }

  private final class Opening implements StateStore<SettlementState> {

    /**
     * 그 호실에 정산이 있으면 열린 상태, 없으면 아직 없는 상태다. 호실을 잠근 뒤라 동시에 열어도 뒤에 온 쪽은 먼저 만든 정산을 본다(03 1-3). 퇴거 점검 시작의
     * {@code when}도 이 값을 본다.
     */
    @Override
    public SettlementState load(ActionCommand command) {
      return settlements.findByRoomRecordId(command.roomRecordId()).isPresent()
          ? SettlementState.OPENED
          : SettlementState.NONE;
    }

    /** 정산을 만든다. 처음 상태는 보증금 확인 전, 정산 모습 1번({@code Settlement} 생성자). */
    @Override
    public SavedState save(ActionCommand command, SettlementState next) {
      Settlement row =
          settlements.save(new Settlement(command.roomRecordId(), requestContext.at()));
      return SavedState.created(ActionTarget.settlement(row.getId()));
    }
  }

  private enum Step {
    PROPOSE,
    CONFIRM,
    NOT_YET,
    ALONE
  }

  private final class Finishing implements StateStore<SettlementState> {

    private final Step step;

    private Finishing(Step step) {
      this.step = step;
    }

    /**
     * 지금 상태. 같은 키로 다시 온 요청은 pipeline이 측을 채우지 않아(재생 결과만 돌려줌) 호실 전체의 사실(정산 완료, 열린 끝내기 요청)만 읽는다. 이때 규칙
     * 검사는 다시 하지 않는다.
     */
    @Override
    public SettlementState load(ActionCommand command) {
      Settlement row = row(command);
      PartySide side = command.actor().side();
      if (side == null) {
        RoomPhase phase = parties.read(row.getRoomRecordId(), PartySide.TENANT).phase();
        PartySide requestedBy =
            openRequest(target(row.getId())).map(RequestView::requestedBySide).orElse(null);
        return new SettlementState(true, requestedBy, phase == RoomPhase.SETTLED, null);
      }
      return current(row, side);
    }

    /**
     * 제안이면 상대에게 끝내기 요청({@code SETTLE_FINISH}, 요청한 측 = 제안한 측)을 연다. 확인·[아직 안 끝났어요]면 그 요청을 답함으로 닫고,
     * 확인·혼자 끝내기면 정산 완료다({@link #settle}). 혼자 끝내기는 혼자 진행 이유를 payload {@code solo_reason}에 남긴다.
     */
    @Override
    public SavedState save(ActionCommand command, SettlementState next) {
      Settlement row = row(command);
      PartySide side = command.actor().side();
      RequestTarget target = target(row.getId());
      switch (step) {
        case PROPOSE ->
            waits.open(
                new OpenRequestCommand(
                    row.getRoomRecordId(), RequestKind.SETTLE_FINISH, target, side));
        case CONFIRM -> {
          waits.answered(target, RequestKind.SETTLE_FINISH, side);
          settle(row.getRoomRecordId());
        }
        case NOT_YET -> waits.answered(target, RequestKind.SETTLE_FINISH, side);
        case ALONE -> {
          SoloReason reason = Objects.requireNonNull(next.facts()).soloReason();
          settle(row.getRoomRecordId());
          return SavedState.unchanged().withPayload(SOLO_REASON, reason.name());
        }
      }
      return SavedState.unchanged();
    }
  }
}
