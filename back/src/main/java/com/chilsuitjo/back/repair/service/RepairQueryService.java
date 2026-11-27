package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.rule.CorrectionTransitions;
import com.chilsuitjo.back.correction.service.CorrectionReader;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.service.ProposalService;
import com.chilsuitjo.back.repair.dto.RepairCaseDetail;
import com.chilsuitjo.back.repair.entity.CauseNote;
import com.chilsuitjo.back.repair.entity.ProgressNote;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.repository.CauseNoteRepository;
import com.chilsuitjo.back.repair.repository.CauseNoteStateStore;
import com.chilsuitjo.back.repair.repository.ProgressNoteRepository;
import com.chilsuitjo.back.repair.repository.RepairAgendaHeadStore;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.repository.RepairContentRepository;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.repository.SituationStateStore;
import com.chilsuitjo.back.repair.rule.CauseNoteTransitions;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.MoneyStatus;
import com.chilsuitjo.back.repair.rule.RepairSight;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import com.chilsuitjo.back.repair.rule.SituationTransitions;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수리 건 조회(06 4-6). 상태를 바꾸지 않아 pipeline을 거치지 않고 읽기 트랜잭션을 쓴다(09 3-1 "트랜잭션 경계": 조회만 하는 서비스는 읽기 트랜잭션을
 * 써도 된다). 행동은 {@link RepairService}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class RepairQueryService {

  /**
   * {@code availableActions}의 이름. API 끝 단어와 같다(06 4-0 "할 수 있는 행동"). 순서는 {@code
   * RepairTransitions.available}이 정한다.
   */
  private static final Map<ActionType, String> ACTION_NAMES =
      Map.of(
          ActionType.RESPOND_DEFECT, "respond",
          ActionType.SUPPLEMENT, "supplement",
          ActionType.START_REPAIR, "start",
          ActionType.REQUEST_CLOSE, "request-close",
          ActionType.CONFIRM_CLOSE, "confirm-close",
          ActionType.NOT_YET, "not-yet",
          ActionType.CLOSE_ALONE, "close-alone",
          ActionType.ASK_PROGRESS, "ask-progress",
          ActionType.NOTE_PROGRESS, "progress-notes");

  /** 원인 설명 남기기·고치기의 {@code availableActions} 이름(06 4-6 {@code cause-notes}). */
  static final String CAUSE_NOTES = "cause-notes";

  /** 고친 내용의 버튼 이름(06 4-6 {@code corrections/{id}/respond}·{@code withdraw}·{@code accept-all}). */
  static final String RESPOND = "respond";

  static final String WITHDRAW = "withdraw";
  static final String RESUBMIT = "resubmit";
  static final String ACCEPT_ALL = "accept-all";

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final RepairStateStore store;
  private final RepairTransitions rules;
  private final RoomGateReader gates;
  private final RoomQueryService rooms;
  private final ActionReader actionReader;
  private final PhotoService photos;
  private final RoomAccessService access;
  private final CauseNoteRepository causeNoteRows;
  private final CauseNoteStateStore causeStore;
  private final CauseNoteTransitions causeRules;
  private final ProgressNoteRepository progressNoteRows;
  private final SituationStateStore situationStore;
  private final SituationTransitions situationRules;
  private final CorrectionReader correctionReader;
  private final ProposalService proposalService;
  private final WaitQuery waits;
  private final Clock clock;

  /**
   * 수리 건 상세(06 4-6, S-12). 없는 ID, 그 호실 당사자가 아님, 상대가 볼 수 없는 기록(공유를 그만둔 뒤 등록, 03 불변식 18)은 모두 {@code
   * NOT_FOUND}다(있는지도 알 수 없게, ROOM-2a).
   *
   * <p>{@code availableActions}는 실제 요청과 같은 판단으로 만든다: 호실 단위 거절은 {@code RoomGate.rejection}(pipeline
   * 3단계와 같음), 수리 건 규칙은 {@code RepairTransitions.available}({@code apply}와 같음). 잠그지 않고 읽으므로 화면을 연 뒤
   * 상태가 바뀔 수 있고, 그때는 요청이 서버에서 다시 검사되어 거절된다.
   *
   * <p><b>공유를 그만둔 뒤</b>(어느 쪽이든, 제한이 살아 있을 때)에는 보이는 상태를 그만둔 시각으로 고정한다(03 6-6 9번 "상태도 그 시각까지로 다시 계산",
   * 3-1 "사진·돈 포함 모든 줄", 양방향). 그 시각까지의 행동과 보는 사람 자신의 행동만으로 다시 계산하고({@code Replay.fold}), 그 시각을
   * {@code asOf}로 돌려준다(화면의 "○월 ○일 기준"). 상대가 그 뒤에 바꾼 하자 인정·수리 진행·부담 상태는 드러나지 않는다(#166 리뷰). {@code
   * availableActions}는 실제 요청과 같아야 해서 지금 상태로 계산한다: 그만둔 쪽은 pipeline이 막아({@code SHARING_STOPPED}) 버튼이
   * 없고, 남은 쪽의 지금 상태에는 그만둔 쪽이 바꾼 것이 없다(그만둔 쪽은 기존 수리 건의 상태를 바꾸지 못함, 03 1-4).
   *
   * <p><b>상대가 탈퇴했으면</b>({@link RepairSight#departed}) 다시 계산하지 않는다: 남은 사람은 제한이 풀려(06 CONS-3p) 실제
   * 상태에서 행동하므로 상태·{@code closeRequestedBy}·버튼은 모두 지금 값이고 {@code asOf}는 null이다(호실 상세 {@code GET
   * /api/rooms/{id}}와 같음). 중단 동안 탈퇴자가 남긴 <b>내용</b>(원인 설명·진행 상황·고친 내용·사진)만 가린다(03 6-4). 그래서 남은 사람이
   * 공유를 그만뒀던 쪽이면 탈퇴자가 중단 동안 바꾼 상태가 지금 값에 섞여 보인다. 탈퇴 처리가 그 행동을 상태에서도 빼면 없어지는 알려진 한계다(decisions
   * 2026-11-27).
   *
   * <p>사진({@code photos})은 수리 건에 붙은 사진이다(하자 등록·보충). 보이는 범위(공유 중단 뒤 고정, 지운 사진 흔적)는 사진 창구가 03 3-1대로
   * 거른다({@code PhotoService.cards}, #175).
   */
  @Transactional(readOnly = true)
  public RepairCaseDetail detail(UUID repairCaseId, UUID memberId) {
    UUID roomRecordId =
        cases
            .findRoomRecordIdById(repairCaseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    RepairCase row =
        cases
            .findByIdAndRoomRecordId(repairCaseId, roomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RepairState state = store.current(row);
    if (state.hiddenFromCounterpartKnown() && gate.side() != state.registeredBy()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    RepairSight sight = store.sightFor(row, gate.side());
    Instant asOf = sight.asOf();
    List<ActionEntry> visible = visibleActions(row, sight);
    // 탈퇴(departed)면 asOf가 null이라 다시 쌓지 않는다: 남은 사람의 행동이 기댄 실제 상태가 줄어든 이력 위에서 깨지지 않게(#294 리뷰)
    RepairState shown = asOf == null ? state : Replay.fold(rules, visible);
    if (state.status() == RepairStatus.IN_PROGRESS) {
      // 혼자 종료 버튼은 실제 혼자 종료와 같은 판단(06 4-0). 측마다 달라 상세에서만 채운다
      state = state.withCanCloseAlone(store.canCloseAlone(row, gate.side()));
    }
    RepairContent content =
        contents
            .findByRepairCaseIdAndNo(row.getId(), shown.contentNo())
            .orElseThrow(() -> new IllegalStateException("보이는 판이 없어요: " + row.getId()));
    // 공유를 그만둔 뒤에는 부위 목록(GET /api/rooms/{id}/parts)과 같게 그 시각의 이름(03 불변식 18, #183)
    String partName =
        rooms.findPartAsSeenBy(roomRecordId, content.getRoomPartId(), memberId).name();
    List<String> actions =
        new ArrayList<>(
            rules.available(state, gate.side(), clock.instant()).stream()
                .filter(type -> gate.rejection(type).isEmpty())
                .map(ACTION_NAMES::get)
                .toList());
    // 원인 설명은 수리 진행과 따로라 상태와 상관없이 남기고 고친다(03 D-2). 호실 단위 거절만 본다
    if (gate.side() != null && gate.rejection(ActionType.NOTE_CAUSE).isEmpty()) {
      actions.add(CAUSE_NOTES);
    }
    RoomAccess viewer = access.require(roomRecordId, memberId);
    List<RepairCaseDetail.Correction> corrections =
        correctionsOf(row, gate, memberId, sight, viewer);
    if (corrections.stream()
        .anyMatch(c ->
            c.status() == CorrectionStatus.WAITING && c.availableActions().contains(RESPOND))) {
      actions.add(ACCEPT_ALL);
    }
    PhotoTarget photoTarget = new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, row.getId());
    List<PhotoCard> photoCards = photos.cards(viewer, List.of(photoTarget)).get(photoTarget);
    return new RepairCaseDetail(
        row.getId(),
        roomRecordId,
        gate.side(),
        row.getCreatedBySide(),
        shown.status(),
        shown.closeRequestedBy(),
        shown.closedBy(),
        closeObjection(shown, visible),
        row.isSolo(),
        row.getCreatedAt(),
        asOf,
        new RepairCaseDetail.Content(
            content.getNo(),
            content.getRoomPartId(),
            partName,
            content.getTitle(),
            content.getDescription()),
        new RepairCaseDetail.Defect(shown.defectAck(), rules.nextRerequestAt(shown)),
        new RepairCaseDetail.Money(moneyAsSeen(row, sight, shown)),
        photoCards,
        causeNotesOf(row, gate, viewer),
        progressNotesOf(row, gate, viewer),
        closeRequestOf(row, sight, shown),
        corrections,
        List.copyOf(actions));
  }

  /**
   * 종료 확인 요청 중 가장 최근 것({@code WaitQuery.latest(CLOSE_CONFIRM, 수리 건)}, 06 4-6, #339). {@code
   * WaitQuery}는 보이는 범위를 보지 않아서 여기서 거른다: 연 측·연 시각이 보는 사람에게 보이지 않으면(공유를 그만둔 뒤 상대가 연 요청) 없는 것으로
   * 본다({@link RepairSight#sees}). 그만둔 사람에게는 새 요청을 만들지 않으므로(03 1-4) 보통은 그대로 보인다.
   *
   * <p>공유를 그만둔 뒤({@code asOf})에는 상세 {@code status}와 같은 시점으로 맞춘다(불변식 18, #344 리뷰): 다시 계산한 상태가 종료 확인
   * 대기인데 요청이 이미 닫혔으면(그 뒤 상대가 확인함 등) 그 시각에는 열려 있던 것이라 열린 모습으로 준다. 기한이 {@code asOf} 전이면 미확인, 아니면
   * 기다림이고 닫힌 이유·시각은 비운다.
   *
   * <p>단, {@code asOf}나 그 전에 닫힌 요청({@code CLOSED}이고 {@code closedAt <= asOf})은 그 시각에도 닫혀 있었으므로 닫힌
   * 이유·시각 그대로 준다. 공유 중단 때 {@code SHARING_STOPPED}로 닫힌 요청은 {@code closedAt == asOf}라 "공유 중단으로 닫힘"으로
   * 보인다(03 B, #382). 답함·거둠은 닫힌 시각이 저장되지 않지만(05 {@code response_request}), 다시 계산한 상태가 종료 확인 대기면 그 답은
   * 보는 사람에게 보이지 않는 행동, 곧 {@code asOf} 뒤의 상대 행동이라 열린 모습으로 되돌린다.
   */
  private RepairCaseDetail.CloseRequest closeRequestOf(
      RepairCase row, RepairSight sight, RepairState shown) {
    Instant asOf = sight.asOf();
    return waits
        .latest(RequestKind.CLOSE_CONFIRM, RequestTarget.of(TargetKind.REPAIR_CASE, row.getId()))
        .filter(view -> sight.sees(view.requestedBySide(), view.openedAt()))
        .map(view -> {
          if (asOf != null
              && shown.status() == RepairStatus.CLOSE_WAIT
              && !view.status().isOpen()
              && !closedBy(view, asOf)) {
            boolean overdue = view.dueAt() != null && !view.dueAt().isAfter(asOf);
            return new RepairCaseDetail.CloseRequest(
                overdue ? RequestStatus.UNCONFIRMED : RequestStatus.WAITING,
                view.openedAt(),
                view.dueAt(),
                overdue ? view.soloReason() : null,
                null,
                null);
          }
          return new RepairCaseDetail.CloseRequest(
              view.status(),
              view.openedAt(),
              view.dueAt(),
              view.soloReason(),
              view.closedReason(),
              view.closedAt());
        })
        .orElse(null);
  }

  /** 이 시각이나 그 전에 닫힌 요청인가({@code CLOSED}만 닫힌 시각이 있다, V1 CHECK). */
  private static boolean closedBy(RequestView view, Instant at) {
    return view.status() == RequestStatus.CLOSED && !view.closedAt().isAfter(at);
  }

  /**
   * 보는 사람에게 보이는 수리비 부담 상태. 고정되지 않았으면 지금 값이다. 공유를 그만둔 뒤({@code asOf})에는 부담 제안 행동이 수리 건이 아니라 제안을 대상으로
   * 해서 {@code RepairTransitions}로는 다시 계산되지 않으므로, 돈 안건 장치가 보이는 제안 행동만으로 다시 계산한 값을 쓴다({@code
   * ProposalService.statusAsSeen}, 03 3-1 "돈 포함 모든 줄"). 수리 건 목록도 같은 함수를 쓴다(06 4-6 "상세와 같음").
   */
  private MoneyStatus moneyAsSeen(RepairCase row, RepairSight sight, RepairState shown) {
    if (sight.asOf() == null) {
      return shown.moneyStatus();
    }
    return RepairAgendaHeadStore.toMoneyStatus(
        proposalService.statusAsSeen(
            row.getRoomRecordId(),
            new AgendaRef(AgendaKind.REPAIR_BURDEN, row.getId()),
            sight::sees));
  }

  /**
   * 내용의 고친 내용(06 REP-7). 버튼은 실제 요청과 같은 판단이다: 호실 단위 거절은 {@code RoomGate.rejection}, 고친 내용 규칙은 {@code
   * CorrectionTransitions.canRespond}·{@code canWithdraw}이고 지금 상태로 계산한다(상세의 {@code
   * availableActions}와 같음). 처리 완료(양측)된 수리 건이면 보류·이견이던 것도 맞아요로 바꾸지 못해 응답 버튼이 없다(REP-7k, #287 리뷰).
   *
   * <p>다시 보낼 때 더한 근거 사진은 그 고친 내용({@code CORRECTION_REQUEST})에 붙어 있어 고친 내용마다 {@code photos}로 준다(원인
   * 설명과 같이 한 번에 부름, #296 리뷰).
   *
   * <p>공유를 그만둔 뒤({@code asOf})에는 그 시각까지 고친 것과 보는 사람 자신이 고친 것만 보이고, 응답도 그 시각까지의 응답과 보는 사람 자신의 응답 중
   * 마지막으로 보여 준다(03 6-6 9번, 불변식 18). 그래서 그만두기 전에 보류·이견이었고 그 뒤 상대가 맞아요로 바꿨으면 보류·이견과 그 사유가 그대로 보인다.
   * 상대가 탈퇴했으면 중단 동안 탈퇴자가 고친 것만 빼고 응답은 지금 값이다({@link RepairSight}, 상세 Javadoc).
   */
  private List<RepairCaseDetail.Correction> correctionsOf(
      RepairCase row, RoomGate gate, UUID memberId, RepairSight sight, RoomAccess access) {
    Instant asOf = sight.asOf();
    PartySide viewer = gate.side();
    boolean closedByBoth =
        row.getStatus() == RepairStatus.DONE && row.getClosedBy() == ClosedBy.BOTH;
    boolean respondable = !closedByBoth && gate.rejection(ActionType.RESPOND_CORRECTION).isEmpty();
    boolean withdrawable = gate.rejection(ActionType.WITHDRAW_CORRECTION).isEmpty();
    boolean resubmittable =
        !closedByBoth && gate.rejection(ActionType.RESUBMIT_CORRECTION).isEmpty();
    List<CorrectionView> all =
        correctionReader.findByRecord(CorrectionTargetKind.REPAIR_CASE, row.getId());
    Set<UUID> canResubmit =
        resubmittable && all.stream().anyMatch(v -> v.status() == CorrectionStatus.REJECTED)
            ? correctionReader.resubmittable(
                row.getRoomRecordId(), all, viewer, store.lastRerequestAt(row), clock.instant())
            : Set.of();
    List<CorrectionView> visible =
        all.stream().filter(view -> sight.sees(view.requestedBySide(), view.createdAt())).toList();
    if (visible.isEmpty()) {
      return List.of();
    }
    List<PhotoTarget> targets =
        visible.stream()
            .<PhotoTarget>map(
                view -> new PhotoTarget.Ref(PhotoTargetType.CORRECTION_REQUEST, view.id()))
            .toList();
    Map<PhotoTarget, List<PhotoCard>> cards = photos.cards(access, targets);
    List<RepairCaseDetail.Correction> result = new ArrayList<>();
    for (CorrectionView view : visible) {
      Shown shown = shownAnswer(view, viewer, asOf);
      List<String> buttons = new ArrayList<>();
      if (respondable
          && CorrectionTransitions.canRespond(view.status(), view.requestedBySide(), viewer)) {
        buttons.add(RESPOND);
      }
      if (withdrawable
          && CorrectionTransitions.canWithdraw(view.status(), view.requestedBySide(), viewer)) {
        buttons.add(WITHDRAW);
      }
      if (canResubmit.contains(view.id())) {
        buttons.add(RESUBMIT);
      }
      String newPartName =
          RepairCorrectable.ROOM_PART_ID.equals(view.field()) && view.newValue() != null
              ? rooms
                  .findPartAsSeenBy(
                      row.getRoomRecordId(), UUID.fromString(view.newValue().toString()), memberId)
                  .name()
              : null;
      result.add(
          new RepairCaseDetail.Correction(
              view.id(),
              view.field(),
              view.newValue(),
              newPartName,
              view.requestedBySide(),
              view.reason(),
              shown.status(),
              shown.answer(),
              shown.reason(),
              shown.at(),
              view.closedReason(),
              view.createdAt(),
              cards.getOrDefault(
                  new PhotoTarget.Ref(PhotoTargetType.CORRECTION_REQUEST, view.id()), List.of()),
              List.copyOf(buttons)));
    }
    return List.copyOf(result);
  }

  /** 보이는 응답 상태. */
  private record Shown(CorrectionStatus status, AckState answer, String reason, Instant at) {}

  /**
   * 보는 사람에게 보이는 응답. 고정되지 않았으면 지금 값이다. 고정됐으면 그 시각까지의 응답과 보는 사람 자신의 응답 중 마지막이고, 그 뒤 상대가 남긴 응답이 있을 때만
   * 다시 계산한다(맞아요 → 확인됨, 보류·이견 → 거절, 없음 → 기다림).
   */
  private static Shown shownAnswer(CorrectionView view, PartySide viewer, Instant asOf) {
    Shown now = new Shown(view.status(), view.answer(), view.answerReason(), view.answeredAt());
    if (asOf == null) {
      return now;
    }
    CorrectionView.Response last = null;
    boolean hidden = false;
    for (CorrectionView.Response response : view.responses()) {
      if (!response.at().isAfter(asOf) || response.side() == viewer) {
        last = response;
      } else {
        hidden = true;
      }
    }
    if (!hidden) {
      return now;
    }
    if (last == null) {
      return new Shown(CorrectionStatus.WAITING, AckState.PENDING, null, null);
    }
    CorrectionStatus status =
        last.answer() == AckState.ACK ? CorrectionStatus.ACCEPTED : CorrectionStatus.REJECTED;
    return new Shown(status, last.answer(), last.reason(), last.at());
  }

  /**
   * 원인 설명(06 REP-8). 측마다 지금 설명(보이는 것 중 다음 수정본이 없는 것) 하나를 남긴 순서로 주고, 고치기 전 설명은 {@code previous}에
   * 새것부터 접는다(REP-8c). 공유를 그만둔 뒤에는 그 시각까지의 설명·응답과 보는 사람 자신의 것만 보인다(03 6-6 9번, 불변식 18). 응답 버튼({@code
   * canRespond})은 실제 요청과 같은 판단이다: 호실 단위 거절은 {@code RoomGate.rejection}, 설명 규칙은 {@code
   * CauseNoteTransitions.canRespond}(지금 설명인지는 보이는 범위가 아니라 실제 기록으로 본다).
   */
  private List<RepairCaseDetail.CauseNote> causeNotesOf(
      RepairCase row, RoomGate gate, RoomAccess viewer) {
    RepairSight visibility = causeStore.visibilityFor(row, gate.side());
    List<CauseNote> visible =
        causeNoteRows.findByRepairCaseIdOrderByCreatedAtAscIdAsc(row.getId()).stream()
            .filter(note -> visibility.sees(note.getSide(), note.getCreatedAt()))
            .toList();
    if (visible.isEmpty()) {
      return List.of();
    }
    Map<UUID, CauseNote> byId = new HashMap<>();
    Set<UUID> replaced = new HashSet<>();
    List<PhotoTarget> targets = new ArrayList<>();
    for (CauseNote note : visible) {
      byId.put(note.getId(), note);
      if (note.getPreviousId() != null) {
        replaced.add(note.getPreviousId());
      }
      targets.add(new PhotoTarget.Ref(PhotoTargetType.CAUSE_NOTE, note.getId()));
    }
    Map<PhotoTarget, List<PhotoCard>> cards = photos.cards(viewer, targets);
    boolean canAnswer = gate.side() != null && gate.rejection(ActionType.RESPOND_CAUSE).isEmpty();
    List<RepairCaseDetail.CauseNote> result = new ArrayList<>();
    for (CauseNote note : visible) {
      if (replaced.contains(note.getId())) {
        continue;
      }
      List<RepairCaseDetail.CauseNote> previous = new ArrayList<>();
      for (CauseNote before = byId.get(note.getPreviousId());
          before != null;
          before = byId.get(before.getPreviousId())) {
        previous.add(causeNoteView(before, visibility, cards, false, List.of()));
      }
      boolean canRespond =
          canAnswer && causeRules.canRespond(causeStore.responseState(note), gate.side());
      result.add(causeNoteView(note, visibility, cards, canRespond, previous));
    }
    return List.copyOf(result);
  }

  /**
   * 진행 상황(06 REP-6). 남긴 순서로 주고, 공유를 그만둔 뒤에는 그 시각까지의 진행 상황·응답과 보는 사람 자신의 것만 보인다(03 6-6 9번, 불변식 18,
   * 원인 설명과 같은 기준). 응답 버튼({@code canRespond})은 실제 요청과 같은 판단이다: 호실 단위 거절은 {@code RoomGate.rejection},
   * 응답 규칙은 {@code SituationTransitions.canRespond}(지금 답은 보이는 범위가 아니라 실제 기록으로 본다).
   */
  private List<RepairCaseDetail.ProgressNote> progressNotesOf(
      RepairCase row, RoomGate gate, RoomAccess viewer) {
    RepairSight visibility = store.sightFor(row, gate.side());
    List<ProgressNote> visible =
        progressNoteRows.findByRepairCaseIdOrderByCreatedAtAscIdAsc(row.getId()).stream()
            .filter(note -> visibility.sees(note.getSide(), note.getCreatedAt()))
            .toList();
    if (visible.isEmpty()) {
      return List.of();
    }
    List<PhotoTarget> targets =
        visible.stream()
            .<PhotoTarget>map(n -> new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, n.getId()))
            .toList();
    Map<PhotoTarget, List<PhotoCard>> cards = photos.cards(viewer, targets);
    boolean canAnswer =
        gate.side() != null && gate.rejection(ActionType.RESPOND_SITUATION).isEmpty();
    List<RepairCaseDetail.ProgressNote> result = new ArrayList<>();
    for (ProgressNote note : visible) {
      RepairCaseDetail.ProgressResponse response =
          situationStore
              .lastResponse(note.getId(), visibility)
              .map(entry ->
                  new RepairCaseDetail.ProgressResponse(
                      AckState.valueOf(entry.payloadAs(AnswerPayload.class).answer().name()),
                      entry.reason(),
                      entry.createdAt()))
              .orElse(null);
      boolean canRespond =
          canAnswer && situationRules.canRespond(situationStore.state(note), gate.side());
      result.add(
          new RepairCaseDetail.ProgressNote(
              note.getId(),
              note.getSide(),
              note.getText(),
              note.isAsksCheck(),
              note.getCreatedAt(),
              cards.getOrDefault(
                  new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, note.getId()), List.of()),
              response,
              canRespond));
    }
    return List.copyOf(result);
  }

  private RepairCaseDetail.CauseNote causeNoteView(
      CauseNote note,
      RepairSight visibility,
      Map<PhotoTarget, List<PhotoCard>> cards,
      boolean canRespond,
      List<RepairCaseDetail.CauseNote> previous) {
    RepairCaseDetail.CauseResponse response =
        causeStore
            .lastResponse(note.getId(), visibility)
            .map(entry ->
                new RepairCaseDetail.CauseResponse(
                    AckState.valueOf(entry.payloadAs(AnswerPayload.class).answer().name()),
                    entry.reason(),
                    entry.createdAt()))
            .orElse(null);
    return new RepairCaseDetail.CauseNote(
        note.getId(),
        note.getSide(),
        note.getText(),
        note.getBasis(),
        note.getExpertName(),
        note.getPreviousId(),
        note.getCreatedAt(),
        response,
        cards.getOrDefault(
            new PhotoTarget.Ref(PhotoTargetType.CAUSE_NOTE, note.getId()), List.of()),
        canRespond,
        List.copyOf(previous));
  }

  /**
   * 보는 사람에게 보이는 이 수리 건의 행동(03 6-6 9번). 고정 시각까지의 행동(그 시각에 남은 것 포함, 불변식 18 "뒤에 남긴 것은 ✕")과 보는 사람 측의
   * 행동이다. 상대가 탈퇴했으면 중단 동안 그 측이 남긴 행동은 계속 뺀다(03 6-4, {@link RepairSight}). 고정되지 않았으면 모두. 행동 기록이 원본이라
   * 같은 규칙({@link RepairTransitions})으로 다시 계산하면 그때의 상태가 나온다(REP-10).
   *
   * <p>내용의 고친 내용에 대한 응답({@code RESPOND_CORRECTION})도 함께 읽는다. 대상이 고친 내용이지만 맞아요가 내용의 새 판을 만들어서, 빼면
   * 보이는 판 번호가 뒤처진다. 순서는 호실의 행동 번호({@code room_seq})다.
   */
  private List<ActionEntry> visibleActions(RepairCase row, RepairSight sight) {
    List<ActionEntry> all =
        new ArrayList<>(actionReader.findByTarget(ActionTarget.repairCase(row.getId())));
    for (CorrectionView correction :
        correctionReader.findByRecord(CorrectionTargetKind.REPAIR_CASE, row.getId())) {
      actionReader.findByTarget(ActionTarget.correctionRequest(correction.id())).stream()
          .filter(entry -> entry.type() == ActionType.RESPOND_CORRECTION)
          .forEach(all::add);
    }
    return all.stream()
        .filter(entry -> sight.sees(entry.actorSide(), entry.createdAt()))
        .sorted(Comparator.comparingLong(ActionEntry::roomSeq))
        .toList();
  }

  /**
   * 혼자 끝낸 처리 완료에 남은 이견(06 REP-5f): 마지막 혼자 종료 뒤의 마지막 [아직 안 됐어요]. 혼자 끝낸 처리 완료가 아니면(양측 확인이 되면 이견은 이력)
   * 없다. 상태 행에 칸이 없고 행동 기록이 원본이다.
   */
  private static RepairCaseDetail.CloseObjection closeObjection(
      RepairState shown, List<ActionEntry> visible) {
    if (!shown.closedAlone()) {
      return null;
    }
    ActionEntry objection = null;
    for (ActionEntry entry : visible) {
      if (entry.type() == ActionType.CLOSE_ALONE) {
        objection = null;
      } else if (entry.type() == ActionType.NOT_YET) {
        objection = entry;
      }
    }
    return objection == null
        ? null
        : new RepairCaseDetail.CloseObjection(
            objection.actorSide(), objection.reason(), objection.createdAt());
  }
}
