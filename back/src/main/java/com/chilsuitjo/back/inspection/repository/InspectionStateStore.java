package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionLock;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
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
import com.chilsuitjo.back.room.service.RoomMoveInDates;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 점검 상태 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * InspectionTransitions}에만).
 */
@Component
@RequiredArgsConstructor
public class InspectionStateStore {

  /** 점검 시작 payload의 종류 키({@code StartInspectionPayload.kind}). */
  public static final String KIND = "kind";

  /**
   * 혼자 잠그기 {@code LOCK} payload의 혼자 진행 이유 키. 값은 {@code SoloReason} 이름이다({@code CLOSE_ALONE}과 같은 키).
   */
  public static final String SOLO_REASON = "solo_reason";

  private final InspectionRepository inspections;
  private final InspectionItemRepository items;
  private final RoomMoveInDates moveInDates;
  private final CounterpartReader parties;
  private final ActionRequestContext requestContext;
  private final ActionReader actions;
  private final WaitService waits;
  private final WaitQuery waitQuery;
  private final InspectionPhotos photos;

  /** 점검 시작(06 INSP-1a·2b)의 저장 방법. 종류와 채울 입주일은 payload에 있다. */
  public StateStore<InspectionState> starting() {
    return new Starting();
  }

  /** 확인 요청(06 4-7 {@code request-confirm}, INSP-1f)의 저장 방법. */
  public StateStore<InspectionState> requesting() {
    return new Requesting();
  }

  /**
   * 혼자 잠그기(06 4-7 {@code lock-alone}, INSP-1g)의 저장 방법. 요청마다 새로 만든다({@code load}에서 받은 혼자 진행 이유를
   * {@code save}가 행동 기록에 남김).
   */
  public StateStore<InspectionState> lockingAlone() {
    return new LockingAlone();
  }

  /**
   * 혼자 잠그기 판단({@code canLockAlone})을 담은 이 점검 행의 지금 상태. 상세 조회의 버튼과 실제 혼자 잠그기가 같은 값을 쓴다(06 4-0 "서버의
   * 같은 함수").
   */
  public InspectionState currentForLockAlone(Inspection row, PartySide side) {
    return current(row).withCanLockAlone(lockAloneReason(row, side).isPresent());
  }

  /**
   * 이 측이 지금 이 점검을 혼자 잠글 수 있으면 그 이유(03 H "잠기는 조건", #392). 판단은 이렇다.
   *
   * <ol>
   *   <li>확인 요청 중인 입주 점검이 아니면 없음(상태 규칙은 {@code InspectionTransitions}가 다시 막는다)
   *   <li>그 점검에 기다리는({@code WAITING}) 확인 요청이 어느 방향이든 남아 있으면 없음. 그 사유의 닫기 규칙이 빠졌다는 뜻이라 되돌릴 수 없는 잠금보다
   *       기다리는 쪽이 안전하다(decisions 2026-11-28)
   *   <li>이 측이 요청한 열린(미확인) 요청이 있으면 {@code canGoSolo}, 없으면 {@code canGoSoloDirectly}(대신하는 요청은 {@code
   *       INSPECTION_CONFIRM}). 어떤 이유가 열리는지는 {@code SoloRule}이 정하고 여기서 다시 거르지 않는다(03 1-1 "함수 하나")
   *   <li>이유가 성립한 시각({@code SoloCheck.since})이 있으면 그 뒤 이 측에게 보이는 상대의 이 점검(항목 포함) 행동이 없어야 한다(03 1-4
   *       "그 뒤로 이 건에 상대 행동이 없으면"). 보이는 범위는 상세와 같이 공유 중단 시각으로 고정한다. 서버 행동은 상대 행동이 아니다
   * </ol>
   */
  private Optional<SoloReason> lockAloneReason(Inspection row, PartySide side) {
    if (side == null
        || row.getKind() != InspectionKind.MOVE_IN
        || row.getStatus() != InspectionStatus.REQUESTED) {
      return Optional.empty();
    }
    UUID room = row.getRoomRecordId();
    RequestTarget target = RequestTarget.of(TargetKind.INSPECTION, row.getId());
    List<RequestView> open = new ArrayList<>();
    for (PartySide answering : PartySide.values()) {
      waitQuery.awaiting(room, answering).stream()
          .filter(view -> view.kind() == RequestKind.INSPECTION_CONFIRM)
          .filter(view -> target.equals(view.target()))
          .forEach(open::add);
    }
    if (open.stream().anyMatch(view -> view.status() == RequestStatus.WAITING)) {
      return Optional.empty();
    }
    SoloQuery query = new SoloQuery(room, RequestKind.INSPECTION_CONFIRM, target, side);
    boolean requestedByMe = open.stream().anyMatch(view -> view.requestedBySide() == side);
    SoloCheck check = requestedByMe ? waits.canGoSolo(query) : waits.canGoSoloDirectly(query);
    if (!check.allowed() || counterpartActedSince(row, side, check.since())) {
      return Optional.empty();
    }
    return Optional.of(check.reason());
  }

  /**
   * {@code since} 뒤에 이 측에게 보이는 상대의 이 점검(항목 포함) 행동이 있나. {@code since}가 없으면 거짓이다. "뒤"는 {@code
   * since}보다 늦은 행동만이고 같은 시각은 세지 않는다(수리 건 혼자 종료와 같은 경계). 보이는 범위는 공유 중단 시각({@code asOf})까지와 이 측 자신의
   * 행동이라, 상대 공유 중단({@code SHARING_STOPPED}, {@code since} = 그만둔 시각)이면 그 뒤 상대 행동은 이 측에게 보이지 않아 막지
   * 않는다. 서버 행동은 상대 행동이 아니다.
   */
  private boolean counterpartActedSince(Inspection row, PartySide side, Instant since) {
    if (since == null) {
      return false;
    }
    Instant asOf = InspectionSight.sharingCutoff(parties.read(row.getRoomRecordId(), side));
    List<ActionTarget> targets = new ArrayList<>();
    targets.add(ActionTarget.inspection(row.getId()));
    items
        .findByInspectionIdOrderByIdAsc(row.getId())
        .forEach(item -> targets.add(ActionTarget.of(TargetKind.INSPECTION_ITEM, item.getId())));
    return actions.findByTargets(row.getRoomRecordId(), targets).stream()
        .anyMatch(entry ->
            entry.actorSide() != null
                && entry.actorSide() != side
                && entry.createdAt().isAfter(since)
                && (asOf == null || !entry.createdAt().isAfter(asOf)));
  }

  /**
   * 지금 상태. 같은 키로 다시 온 요청이면 처음 만든 점검을 읽는다. 처음 요청이면 그 호실의 같은 종류 점검을 찾고(있으면 {@code apply}가 {@code
   * INSPECTION_EXISTS}로 막는다), 없으면 호실 사실(입주일이 비었나, 거주 종료 뒤인가)만 담는다. 호실을 잠근 뒤라 동시에 시작해도 뒤에 온 쪽은 먼저 만든
   * 점검을 본다(03 1-3).
   */
  InspectionState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target != null) {
      if (target.kind() != TargetKind.INSPECTION) {
        throw new IllegalArgumentException("점검이 아닌 대상이에요: " + target);
      }
      return current(row(command));
    }
    return beforeStart(
        command.roomRecordId(),
        kindOf(command),
        Objects.requireNonNull(command.actor().side(), "side"));
  }

  /**
   * 시작하기 전에 보는 그 호실·종류의 지금 상태. 이미 점검이 있으면 그 점검, 없으면 호실 사실(입주일이 비었나, 거주 종료 뒤인가)만 담는다. 시작 요청({@link
   * #load})과 호실 상세의 시작 버튼({@code InspectionStartCheck})이 같은 값을 읽는다(06 4-0 "서버의 같은 함수").
   */
  public InspectionState beforeStart(UUID roomRecordId, InspectionKind kind, PartySide side) {
    return inspections
        .findByRoomRecordIdAndKind(roomRecordId, kind)
        .map(this::current)
        .orElseGet(() -> notStarted(roomRecordId, side));
  }

  /**
   * 이 점검 행의 지금 상태({@link #load}와 같은 값). 상세 조회가 {@code availableActions}를 계산할 때도 쓴다(06 4-0 "서버의 같은
   * 함수"). 지금 호실 단계에서 점검 확인 요청을 열 수 있는지를 함께 담는다(03 K).
   */
  public InspectionState current(Inspection row) {
    RoomParties room = parties.read(row.getRoomRecordId(), row.getAuthorSide());
    boolean canOpen =
        room.canOpen(RequestKind.INSPECTION_CONFIRM)
            && waits.canOpen(row.getRoomRecordId(), RequestKind.INSPECTION_CONFIRM);
    return new InspectionState(
        row.getKind(),
        row.getStatus(),
        row.getAuthorSide(),
        row.getCreatedAt(),
        null,
        null,
        canOpen,
        null,
        row.isLate(),
        null);
  }

  private InspectionState notStarted(UUID roomRecordId, PartySide side) {
    LocalDate moveInDate = moveInDates.find(roomRecordId).orElse(null);
    RoomPhase phase = parties.read(roomRecordId, side).phase();
    return InspectionState.notStarted(moveInDate, phase != RoomPhase.LIVING);
  }

  private Inspection row(ActionCommand command) {
    return inspections
        .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static InspectionKind kindOf(ActionCommand command) {
    Object kind = command.payload().get(KIND);
    if (kind == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return InspectionKind.valueOf(kind.toString());
  }

  private final class Starting implements StateStore<InspectionState> {

    @Override
    public InspectionState load(ActionCommand command) {
      return InspectionStateStore.this.load(command);
    }

    /**
     * 점검을 작성 중으로 만든다. 입주일이 비어 있던 호실이면 같은 트랜잭션에서 입주일을 채운다(06 4-7). 채운 입주일은 이미 행동 payload에 있어 그 기록이
     * 원본이다({@code RoomMoveInDates}). 작성 중이라 상대에게 아직 보이지 않는다({@code first_shown_at} 없음, 06 INSP-1a).
     * 늦은 입주 표시는 규칙이 정한 값 그대로다(INSP-2a).
     */
    @Override
    public SavedState save(ActionCommand command, InspectionState next) {
      Object date = command.payload().get(RoomMoveInDates.FILLED_PAYLOAD_KEY);
      if (date != null) {
        moveInDates.fill(command.roomRecordId(), LocalDate.parse(date.toString()));
      }
      Inspection row =
          inspections.save(
              new Inspection(
                  command.roomRecordId(),
                  next.kind(),
                  next.authorSide(),
                  Boolean.TRUE.equals(next.late()),
                  requestContext.at()));
      return SavedState.created(ActionTarget.inspection(row.getId()));
    }
  }

  private final class Requesting implements StateStore<InspectionState> {

    @Override
    public InspectionState load(ActionCommand command) {
      return InspectionStateStore.this.load(command);
    }

    /**
     * 점검을 확인 요청으로 바꾼다. 상태는 상대 자리와 상관없이 바뀌고, 같은 트랜잭션에서 점검 확인 요청({@code INSPECTION_CONFIRM}, 요청한 측 =
     * 기준 측)을 열지와 {@code first_shown_at}을 채울지는 상대 자리로 나눈다(#163 6-1 D 추천).
     *
     * <ul>
     *   <li>공유 중단 제한이 살아 있으면(어느 쪽이든) 요청을 열지 않는다(03 B "그만둔 사람에게는 새 확인 요청을 만들지 않는다", 불변식 18). 다시 공유하면
     *       그때 요청이 생긴다(03 1-4 "다시 공유한 뒤 새 확인 요청")
     *   <li>연결됨: 요청을 열고 {@code first_shown_at}을 행동 시각으로 채운다(05 2-10, decisions 2026-11-23과 같은 기준)
     *   <li>연결 전({@code INVITED}): 요청을 연다(03 1-4 "연결 전 상대" ① "링크로 알리고 기다린다", 06 ROOM-4c). 점검이 아직
     *       상대에게 보이지 않아 {@code first_shown_at}은 비워 두고 연결할 때 채운다. 기다림은 요청을 만든 시각이 아니라 첫 통지가 전달된 시각부터
     *       센다(05 {@code first_notified_at}, D 담당)
     *   <li>상대 없음({@code EMPTY}·{@code NUMBER_ONLY})·거절·탈퇴: 받을 사람이 없어 요청을 열지 않는다. 혼자 잠금은 잠금 작업에서
     *       {@code canGoSoloDirectly}로 판단한다
     * </ul>
     *
     * <p>상대 자리와 상관없이 작성 중에 붙인 사진의 {@code released_at}을 행동 시각으로 채운다(#295). 사진이 보이는지는 그 시각과 공유 중단
     * 시각으로 photo가 판단해서 항목({@code InspectionSight})과 같은 때 보이고 같은 때 가려진다.
     *
     * <p><b>현재 구현 범위의 한계</b>: 상대 측이 "지금까지 쓴 것으로" 보냈을 때 기준 측에 가는 서비스 안 알림(06 4-7)은 아직 보내지 않는다. 지금
     * {@code InAppNotifier}는 메모리에만 쌓는 가짜라 사용자가 볼 수 없어서, 실제 저장이 들어오는 U3-2 뒤에 주제를 정해 연결한다(#116 D 제안
     * (가), #234 리뷰). 그때도 공유 중단 제한이 살아 있으면 보내지 않는다(불변식 18).
     *
     * <p>이 행동은 판을 보지 않아 내용 지문이 없다(안내서 3-3).
     */
    @Override
    public SavedState save(ActionCommand command, InspectionState next) {
      Inspection row = row(command);
      row.changeStatus(next.status());
      PartySide author = row.getAuthorSide();
      RoomParties room = parties.read(command.roomRecordId(), author);
      Instant at = requestContext.at();
      RequestTarget target = RequestTarget.of(TargetKind.INSPECTION, row.getId());
      boolean stopped = InspectionSight.sharingStopped(room);
      if (!stopped && room.counterpart().presence() == PartyPresence.CONNECTED) {
        row.markShown(at);
      }
      // 작성 중에 붙인 사진도 항목과 같은 때부터 보인다. 공유를 그만둔 뒤면 그 시각보다 뒤라 계속 가려진다(#295, 불변식 18)
      photos.release(command.roomRecordId(), row.getId(), at);
      if (InspectionSight.reachable(room)) {
        waits.open(
            new OpenRequestCommand(
                command.roomRecordId(), RequestKind.INSPECTION_CONFIRM, target, author));
      }
      return SavedState.unchanged();
    }
  }

  /**
   * 혼자 잠그기(06 4-7 {@code lock-alone}, 03 H "잠기는 조건", #392). {@code load}는 pipeline이 호실을 잠근 뒤 같은
   * 트랜잭션에서 불러서 {@code canGoSolo}·{@code canGoSoloDirectly}의 "호실을 잠근 뒤" 전제를 지킨다. 판단 결과는 {@code
   * canLockAlone}에 담고 규칙이 막는다.
   */
  private final class LockingAlone implements StateStore<InspectionState> {

    private SoloReason reason;

    @Override
    public InspectionState load(ActionCommand command) {
      Inspection row = row(command);
      PartySide side = command.actor().side();
      reason = lockAloneReason(row, side).orElse(null);
      return current(row).withCanLockAlone(reason != null);
    }

    /**
     * 점검을 잠그고({@code lock_reason} {@code SOLO}) 혼자 진행 이유를 payload {@code solo_reason}에 남긴다(05 2-3,
     * {@code SoloReason}, {@code CLOSE_ALONE}과 같은 키). 되풀이가 행동만으로 잠긴 이유를 다시 만들 수 있게 {@code
     * lockReason}도 함께 남긴다. 응답 요청은 새로 열거나 닫지 않는다. 도장이 없던 항목은 상태를 그대로 두고 지금 판에 도장을 찍는다(03 G "점검 전체",
     * 서버 잠금과 같음).
     */
    @Override
    public SavedState save(ActionCommand command, InspectionState next) {
      if (reason == null) {
        // 규칙(InspectionTransitions.lockAlone)이 canLockAlone=false를 먼저 막으므로 오면 구현 버그다
        throw new IllegalStateException("혼자 진행 이유 없이 혼자 잠글 수 없어요");
      }
      Inspection row = row(command);
      for (InspectionItem item : items.findByInspectionIdOrderByIdAsc(row.getId())) {
        if (item.getConfirmedVersionId() == null) {
          item.stampCurrent();
        }
      }
      row.lock(requestContext.at(), LockReason.SOLO);
      return SavedState.unchanged()
          .withPayload(InspectionLock.LOCK_REASON, LockReason.SOLO.name())
          .withPayload(SOLO_REASON, reason.name());
    }
  }
}
