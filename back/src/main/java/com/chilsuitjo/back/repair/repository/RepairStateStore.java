package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.action.service.SupplementWriter;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.CorrectionCloser;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.repair.entity.CauseNote;
import com.chilsuitjo.back.repair.entity.ProgressNote;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.rule.RepairSight;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import com.chilsuitjo.back.repair.rule.SituationCheck;
import com.chilsuitjo.back.repair.rule.SituationOpening;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수리 건 상태 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * RepairTransitions}에만).
 *
 * <p>제목·설명 같은 내용은 행동 payload에 넣지 않고 판({@code repair_content})에만 둔다(05 2-3 "payload는 작은 부가 값만", 04
 * "한 사실은 한 곳에"). 그래서 하자 등록·보충은 요청마다 내용을 담은 저장 방법을 만들어 pipeline에 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class RepairStateStore {

  /** 판 내용 지문의 판 종류(05 2-3 「해시 입력 형식」 내용 지문). */
  static final String CONTENT_KIND = "repair_content";

  /** 진행 상황 내용 지문의 판 종류(05 2-3 「해시 입력 형식」 내용 지문). */
  static final String PROGRESS_KIND = "progress_note";

  /** 혼자 종료 행동 payload의 혼자 진행 이유 키(05 2-3, 값은 {@code SoloReason} 이름). */
  public static final String SOLO_REASON = "solo_reason";

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final ProgressNoteRepository notes;
  private final CauseNoteRepository causeNotes;
  private final RoomQueryService rooms;
  private final CounterpartReader parties;
  private final ActionRequestContext requestContext;
  private final ActionReader actions;
  private final WaitService waits;
  private final SupplementWriter supplementWriter;
  private final RepairPhotos photos;
  private final CorrectionCloser corrections;

  /**
   * 하자 등록(06 REP-1)의 저장 방법. 같은 키로 다시 온 요청이면 pipeline이 만든 수리 건을 {@code load}로 다시 읽는다.
   *
   * @param uploadTickets 붙일 사진의 업로드 표 1~10개(장수는 서비스·요청 본문이 먼저 본다)
   */
  public StateStore<RepairState> registering(
      UUID roomPartId, String title, String description, List<String> uploadTickets) {
    return new Registering(
        Objects.requireNonNull(roomPartId, "roomPartId"),
        Objects.requireNonNull(title, "title"),
        description,
        List.copyOf(uploadTickets));
  }

  /** 하자 응답(06 REP-2a~d·f)의 저장 방법. 하자 인정만 바꾸고 하자 확인 요청을 "답함"으로 닫는다. */
  public StateStore<RepairState> responding() {
    return new Responding();
  }

  /**
   * 보충하고 다시 확인 요청(06 REP-2e)의 저장 방법. 설명을 보충 기록에 남기고 하자 확인 요청을 다시 연다.
   *
   * @param description 덧붙인 설명. 사진만 보충했으면 null
   * @param uploadTickets 덧붙인 사진의 업로드 표. 설명만 보충했으면 빈 목록
   */
  public StateStore<RepairState> supplementing(String description, List<String> uploadTickets) {
    return new Supplementing(description, List.copyOf(uploadTickets));
  }

  /**
   * 수리 진행(06 REP-4·5: 시작·종료 요청·종료 확인·아직 안 됐어요)의 저장 방법. 수리 진행과 끝낸 측을 바꾸고, 종료 요청이면 종료 확인 요청을 열고, 종료
   * 확인·아직 안 됐어요면 그 요청을 "답함"으로 닫는다.
   */
  public StateStore<RepairState> progressing() {
    return new Progressing();
  }

  /** 혼자 종료(06 REP-5e)의 저장 방법. 요청마다 새로 부른다. */
  public StateStore<RepairState> closingAlone() {
    return new ClosingAlone();
  }

  /** 진행 상황 묻기(06 REP-6b)의 저장 방법. 상대에게 진행 상황 묻기 요청을 연다. */
  public StateStore<RepairState> askingProgress() {
    return new AskingProgress();
  }

  /**
   * 진행 상황 남기기(06 REP-6)의 저장 방법. 진행 상황 한 줄을 남기고 사진을 그 줄에 붙인다. [상황이 바뀌었어요]를 켜면 그 진행 상황을 대상으로 상대에게 상황
   * 변화 확인 요청({@code SITUATION_CHECK})을 연다(REP-6d).
   *
   * @param text 남긴 글. 사진만 남겼으면 빈 글자({@code progress_note.text}가 NOT NULL)
   * @param uploadTickets 붙일 사진의 업로드 표. 글만 남겼으면 빈 목록
   * @param asksCheck [상황이 바뀌었어요]를 켰는지
   */
  public NotingProgress notingProgress(String text, List<String> uploadTickets, boolean asksCheck) {
    return new NotingProgress(
        Objects.requireNonNull(text, "text"), List.copyOf(uploadTickets), asksCheck);
  }

  /**
   * 응답할 때 본 판의 내용 지문 재료(본 판에 대고 하는 행동은 그 판, 안내서 3-3). 판은 고칠 수 없어서(B) pipeline 잠금 전에 읽어도 값이 같다. 그런
   * 판이 없으면 빈 값이고, 그때는 {@code apply}가 판 번호가 달라 {@code VERSION_CONFLICT}로 막는다.
   */
  public Optional<DigestInput> seenContent(UUID repairCaseId, Integer seenVersionNo) {
    if (seenVersionNo == null) {
      return Optional.empty();
    }
    return contents
        .findByRepairCaseIdAndNo(repairCaseId, seenVersionNo)
        .map(RepairStateStore::digest);
  }

  /**
   * 지금 상태. 대상이 없으면(만드는 행동) 빈 상태다. 다른 호실의 수리 건 ID는 {@code NOT_FOUND}(ROOM-2a).
   *
   * <p>지금 판 번호, 마지막 보충 시각(행동 기록이 원본, 안내서 8-1), 지금 하자 확인 요청을 열 수 있는지(03 K)를 함께 담는다.
   */
  RepairState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target == null) {
      return RepairState.NONE;
    }
    if (target.kind() != TargetKind.REPAIR_CASE) {
      throw new IllegalArgumentException("수리 건이 아닌 대상이에요: " + target);
    }
    return current(row(command));
  }

  /**
   * 이 수리 건 행의 지금 상태({@link #load}와 같은 값). 상세 조회가 {@code availableActions}를 계산할 때도 쓴다(06 4-0 "서버의 같은
   * 함수"). 트랜잭션 안에서 부른다(행동 기록 읽기가 {@code MANDATORY}).
   */
  public RepairState current(RepairCase row) {
    int contentNo =
        contents
            .findById(row.getCurrentContentId())
            .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + row.getId()))
            .getNo();
    List<ActionEntry> history = actions.findByTarget(ActionTarget.repairCase(row.getId()));
    RoomParties room = parties.read(row.getRoomRecordId(), row.getCreatedBySide());
    return new RepairState(
        row.getStatus(),
        row.getDefectAck(),
        row.getMoneyStatus(),
        row.getCreatedBySide(),
        contentNo,
        lastAt(history, ActionType.SUPPLEMENT),
        row.getClosedBy(),
        closeRequestedBy(row, history),
        lastAt(history, ActionType.ASK_PROGRESS),
        canOpen(room, row.getRoomRecordId(), RequestKind.DEFECT_CONFIRM),
        canOpen(room, row.getRoomRecordId(), RequestKind.CLOSE_CONFIRM),
        canOpen(room, row.getRoomRecordId(), RequestKind.PROGRESS_CHECK),
        hiddenFromCounterpart(room, row),
        null,
        new SituationCheck(lastSituationCheckAt(history), null));
  }

  /**
   * 마지막으로 [상황이 바뀌었어요]를 켜서 진행 상황을 남긴 시각(행동 기록이 원본: {@code NOTE_PROGRESS}의 payload {@code
   * asksCheck}). 누가 켰든 한 건에 센다(06 REP-6d). 없으면 null.
   */
  private static Instant lastSituationCheckAt(List<ActionEntry> history) {
    return history.stream()
        .filter(entry ->
            entry.type() == ActionType.NOTE_PROGRESS
                && Boolean.TRUE.equals(
                    entry.payloadAs(RepairTransitions.ProgressPayload.class).asksCheck()))
        .map(ActionEntry::createdAt)
        .max(Instant::compareTo)
        .orElse(null);
  }

  /**
   * 이 측이 지금 상황 변화 확인 요청을 열 수 있나(06 REP-6d). 내가 공유를 그만뒀으면 요청을 열지 않고(03 B), 거주 종료 뒤처럼 호실 단계에서 열 수
   * 없거나(03 K) 공유 중단 제한이 살아 있어 지금 남기는 진행 상황이 상대에게 보이지 않으면(불변식 18) 열 수 없다.
   */
  private SituationOpening situationOpening(UUID roomRecordId, PartySide side) {
    RoomParties room = parties.read(roomRecordId, side);
    if (room.mySharingStoppedApplies()) {
      return SituationOpening.SHARING_STOPPED;
    }
    if (sharingCutoff(room) != null || !canOpen(room, roomRecordId, RequestKind.SITUATION_CHECK)) {
      return SituationOpening.BLOCKED;
    }
    return SituationOpening.OPEN;
  }

  /**
   * 이 측이 지금 이 수리 건을 혼자 종료할 수 있나(06 REP-5e, 03 1-4 "물을 상대가 없는 건의 종료"). 상세 조회의 버튼과 실제 혼자 종료가 같은 판단을
   * 쓴다(06 4-0). 수리 진행 상태는 보지 않는다(그건 {@code RepairTransitions}).
   */
  public boolean canCloseAlone(RepairCase row, PartySide side) {
    return soloAllowed(row, side, soloCheck(row, side));
  }

  /**
   * 바로 혼자 진행 판단({@code WaitService.canGoSoloDirectly}, 대신하는 요청은 {@code CLOSE_CONFIRM}). 수리는 이 결과의
   * 이유를 다시 거르지 않는다(#113 ③', 03 1-1 "함수 하나").
   */
  private SoloCheck soloCheck(RepairCase row, PartySide side) {
    return waits.canGoSoloDirectly(
        new SoloQuery(
            row.getRoomRecordId(),
            RequestKind.CLOSE_CONFIRM,
            RequestTarget.of(TargetKind.REPAIR_CASE, row.getId()),
            side));
  }

  /**
   * 허용이고, 이유가 성립한 시각({@code SoloCheck.since})이 있으면 그 뒤 이 건에 행동하는 측에게 보이는 상대 행동이 없을 때만 참이다(03 1-4 "그
   * 뒤로 이 건에 상대 행동이 없으면", {@code SoloCheck} 설명). 이 건의 행동은 수리 건 대상 행동(진행 상황 포함)과 그 건의 원인 설명 대상 행동이다.
   * 보이는 범위는 상세와 같이 공유 중단 시각으로 고정한다(3-1). 서버 행동은 상대 행동이 아니다.
   */
  private boolean soloAllowed(RepairCase row, PartySide side, SoloCheck check) {
    if (side == null || !check.allowed()) {
      return false;
    }
    if (check.since() == null) {
      return true;
    }
    RepairSight sight = sightFor(row, side);
    List<ActionEntry> history =
        new ArrayList<>(actions.findByTarget(ActionTarget.repairCase(row.getId())));
    for (CauseNote note : causeNotes.findByRepairCaseIdOrderByCreatedAtAscIdAsc(row.getId())) {
      history.addAll(actions.findByTarget(ActionTarget.of(TargetKind.CAUSE_NOTE, note.getId())));
    }
    return history.stream()
        .noneMatch(entry ->
            entry.actorSide() != null
                && entry.actorSide() != side
                && entry.createdAt().isAfter(check.since())
                && sight.sees(entry.actorSide(), entry.createdAt()));
  }

  /** 이 종류의 마지막 행동 시각(행동 기록이 원본, 안내서 8-1). 없으면 null. */
  private static Instant lastAt(List<ActionEntry> history, ActionType type) {
    return history.stream()
        .filter(entry -> entry.type() == type)
        .map(ActionEntry::createdAt)
        .max(Instant::compareTo)
        .orElse(null);
  }

  /**
   * 지금 종료를 요청한 측: 종료 확인 대기일 때 마지막 {@code REQUEST_CLOSE}의 측(행동 기록이 원본, 상태 행에 칸이 없음). 대기가 아니면 null.
   * 행동은 {@code room_seq} 순이라 마지막 것이 지금 요청이다.
   */
  private static PartySide closeRequestedBy(RepairCase row, List<ActionEntry> history) {
    if (row.getStatus() != RepairStatus.CLOSE_WAIT) {
      return null;
    }
    PartySide side = null;
    for (ActionEntry entry : history) {
      if (entry.type() == ActionType.REQUEST_CLOSE) {
        side = entry.actorSide();
      }
    }
    return side;
  }

  /**
   * 지금 이 종류의 확인 요청을 열 수 있나(03 K "거주 종료 뒤", 1-4). 호실 단계는 {@code CounterpartReader}가 실제 호실 상태로
   * 돌려주고({@code RoomParties.canOpen} = {@code RequestOpening}), 그 위에 notification이 막아 둔 것도 함께
   * 본다({@code WaitService.canOpen}). 지금 {@code FakeWaitService}는 호실 단계를 보지 않아서, 호실 단계를 여기서도 봐야 거주
   * 종료 뒤 보충·종료 요청이 막힌다(#157 리뷰).
   */
  private boolean canOpen(RoomParties room, UUID roomRecordId, RequestKind kind) {
    return room.canOpen(kind) && waits.canOpen(roomRecordId, kind);
  }

  /**
   * 상대 측이 이 기록을 볼 수 없나(03 불변식 18): 제한이 살아 있는 공유 중단 시각 중 이른 것보다 뒤에 등록한 기록이면 참이다. 공유 중단은 양방향이라 어느 쪽이
   * 그만뒀든 같다. 상대가 탈퇴해 풀린 공유 중단은 보지 않는다({@code RoomParties}의 판단, 06 CONS-3p·3q). 연결 전에 남긴 기록은 연결하면
   * 보이므로(03 1-4) 연결 여부는 보지 않는다.
   */
  private static boolean hiddenFromCounterpart(RoomParties room, RepairCase row) {
    Instant stopped = sharingCutoff(room);
    return stopped != null && row.getCreatedAt().isAfter(stopped);
  }

  /**
   * 보는 사람에게 이 호실 수리 기록이 어디까지 보이나({@link RepairSight}, 03 6-6 9번 "그만둔 시각 기준으로 고정", 3-1 "모든 줄에 같게",
   * 6-4). 탈퇴로 풀리는지는 <b>보는 사람 기준</b>이다({@code RoomParties}가 "내 측"에서 판단, #175). 상대가 탈퇴했으면 남아 있는 공유 중단
   * 시각 뒤의 그 측 기록은 계속 보이지 않는다(#288).
   *
   * @param viewer 보는 사람의 측
   */
  public RepairSight sightFor(RepairCase row, PartySide viewer) {
    return RepairSight.of(parties.read(row.getRoomRecordId(), viewer), viewer);
  }

  /**
   * 제한이 살아 있는 공유 중단 시각 중 이른 것({@link RepairSight#shared}). 지금 할 수 있는 일(요청 열기)과 상대가 볼 수 있는지를 정한다.
   * 상대가 탈퇴해 풀린 공유 중단은 보지 않는다(06 CONS-3p·3q).
   */
  private static Instant sharingCutoff(RoomParties room) {
    return RepairSight.of(room, null).shared();
  }

  private RepairCase row(ActionCommand command) {
    return cases
        .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /**
   * 상대가 지금 이 기록을 볼 수 있나(03 3-1 "내가 남긴 기록"): 상대 측 회원이 연결돼 있고, 두 측 모두 공유 중단 제한이 없을 때. 연결 전·초대 전·나만
   * 보기면 보이지 않는다.
   */
  private boolean counterpartSeesNow(UUID roomRecordId, PartySide mySide) {
    RoomParties room = parties.read(roomRecordId, mySide);
    return room.counterpart().presence() == PartyPresence.CONNECTED
        && !room.mySharingStoppedApplies()
        && !room.counterpartSharingStoppedApplies();
  }

  private static RequestTarget defectRequest(ActionCommand command) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, command.target().id());
  }

  /**
   * 상대가 이 건에 무엇이든 남기면 진행 상황 묻기 요청이 닫힌다(06 REP-6c, 03 D "그 건에 상대가 남긴 행동은 모두 응답으로 본다"). 이미 있는 수리 건에
   * 남기는 모든 저장 방법이 부른다. 물은 측이 남긴 것은 답이 아니라서, 열린 요청이 없으면 아무것도 하지 않는다({@code WaitService#answered}).
   *
   * <p>답이 된 진행 상황의 {@code answers_request_id}는 채우지 않는다: {@code WaitService}가 열린 요청의 ID를 돌려주는 창구가
   * 없다(#219).
   */
  private void answerProgressCheck(ActionCommand command) {
    answerProgressCheck(command.target().id(), command.actor().side());
  }

  /**
   * 행동 대상이 수리 건이 아닌 저장 방법(원인 설명 남기기·응답, {@code CauseNoteStateStore})이 부모 수리 건의 진행 상황 묻기를 닫을 때 쓴다(06
   * REP-6c). 규칙은 {@link #answerProgressCheck(ActionCommand)}와 같다.
   */
  void answerProgressCheck(UUID repairCaseId, PartySide side) {
    waits.answered(
        RequestTarget.of(TargetKind.REPAIR_CASE, repairCaseId), RequestKind.PROGRESS_CHECK, side);
  }

  /**
   * 판의 내용 지문 재료. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3, 테스트로 고정). 고치기({@code RepairCorrectable})도 쓴다.
   */
  public static DigestInput digest(RepairContent content) {
    return new DigestInput(
        CONTENT_KIND,
        content.getId(),
        content.getNo(),
        List.of(
            new DigestField("room_part_id", content.getRoomPartId().toString()),
            new DigestField("title", content.getTitle()),
            new DigestField("description", content.getDescription())));
  }

  private final class Registering implements StateStore<RepairState> {

    private final UUID roomPartId;
    private final String title;
    private final String description;
    private final List<String> uploadTickets;

    private Registering(
        UUID roomPartId, String title, String description, List<String> uploadTickets) {
      this.roomPartId = roomPartId;
      this.title = title;
      this.description = description;
      this.uploadTickets = uploadTickets;
    }

    @Override
    public RepairState load(ActionCommand command) {
      return RepairStateStore.this.load(command);
    }

    /**
     * 머리와 판 #1을 한 트랜잭션에 만든다. 서로를 가리켜서 두 ID를 저장 전에 만든다(05 0장 "머리·판 네 쌍", 지연 FK). 시각은 행동 기록과 같은
     * {@code at()}이다. 내용 지문은 새로 만든 판 #1이다(안내서 3-3).
     *
     * <p>사진은 머리·판을 DB에 내보낸 뒤 수리 건에 붙인다(사진 행이 수리 건을 가리키므로, REP-1a "한 트랜잭션에 수리 건·판·사진·행동"). 사진 지문은 행동
     * payload {@code photos}로 남는다(05 2-3). 사진이 실패하면 머리·판도 함께 되돌려진다.
     */
    @Override
    public SavedState save(ActionCommand command, RepairState next) {
      UUID roomRecordId = command.roomRecordId();
      // 남의 부위·없는 부위는 NOT_FOUND(RoomQueryService). 숨긴 부위는 목록에서 빠지니 새 기록을 받지 않는다(06 ROOM-3a).
      // 꺼 둔 설비·가전은 "이 집에 없음"이라 새 하자를 받지 않는다. 켠 뒤 등록한다(06 ROOM-3d, #182). 이미 있는 수리 건은 끄더라도 그대로다.
      // 공유를 그만둔 뒤에는 등록하는 사람에게 보이는 부위 상태로 본다: 그 뒤 상대가 바꾼 부위가 드러나지 않게(03 6-6 9번, 불변식 18, #183)
      RoomPartSummary seen =
          rooms.findPartAsSeenBy(roomRecordId, roomPartId, command.actor().memberId());
      if (seen.hidden() || !seen.enabled()) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      Instant at = requestContext.at();
      PartySide side = next.registeredBy();
      UUID caseId = Uuids.v7();
      UUID contentId = Uuids.v7();

      RepairCase head = new RepairCase(caseId, roomRecordId, contentId, side, null, at);
      if (counterpartSeesNow(roomRecordId, side)) {
        head.markShown(at);
      }
      cases.save(head);
      RepairContent first =
          new RepairContent(
              contentId, roomRecordId, caseId, 1, roomPartId, title, description, null, side, at);
      contents.save(first);
      cases.flush();

      return new SavedState(ActionTarget.repairCase(caseId), digest(first))
          .withPhotos(photos.attach(command, caseId, uploadTickets));
    }
  }

  private final class Responding implements StateStore<RepairState> {

    @Override
    public RepairState load(ActionCommand command) {
      return RepairStateStore.this.load(command);
    }

    /**
     * 하자 인정을 바꾸고(수리 진행은 그대로, REP-2a) 응답한 판에 도장을 찍는다(03 1-3 "[맞아요]·보류·이견 모두 도장", #157 리뷰). 열린 하자 확인
     * 요청이 있으면 "답함"으로 닫는다. 늦은 답이나 요청 없이 먼저 답한 경우에는 notification이 아무것도 하지 않는다({@code
     * WaitService#answered}). 내용 지문은 서비스가 넘긴 본 판이다. 등록한 측에게 가는 서비스 안 알림은 알리기 PR에서 붙인다.
     */
    @Override
    public SavedState save(ActionCommand command, RepairState next) {
      RepairCase row = row(command);
      row.changeDefectAck(next.defectAck());
      row.stampCurrent();
      waits.answered(defectRequest(command), RequestKind.DEFECT_CONFIRM, command.actor().side());
      answerProgressCheck(command);
      return SavedState.unchanged();
    }
  }

  /**
   * 혼자 종료(06 REP-5e)의 저장 방법. 요청마다 새로 만든다({@code load}에서 받은 혼자 진행 판단을 {@code save}가 행동 기록에 남김).
   *
   * <p>{@code load}는 pipeline이 호실을 잠근 뒤 같은 트랜잭션에서 불러서({@code ActionPipeline} 1 → 5), {@code
   * canGoSoloDirectly}의 "호실을 잠근 뒤" 전제를 지킨다. 판단 결과는 {@code canCloseAlone}에 담고 규칙이 막는다.
   */
  private final class ClosingAlone implements StateStore<RepairState> {

    private SoloCheck check;

    @Override
    public RepairState load(ActionCommand command) {
      RepairState state = RepairStateStore.this.load(command);
      PartySide side = command.actor().side();
      if (side == null) {
        return state.withCanCloseAlone(false);
      }
      RepairCase row = row(command);
      check = soloCheck(row, side);
      return state.withCanCloseAlone(soloAllowed(row, side, check));
    }

    /**
     * 처리 완료(○○ 확인)로 바꾸고 혼자 진행 이유를 payload {@code solo_reason}에 남긴다(05 2-3, {@code SoloReason}). 요청은
     * 만들지 않는다. 상대에게 가는 서비스 안 알림(문자 없음)은 알리기 PR에서 붙인다. 보류·이견이 열린 채면 강행 표시도 남긴다.
     */
    @Override
    public SavedState save(ActionCommand command, RepairState next) {
      row(command).changeProgress(next.status(), next.closedBy());
      answerProgressCheck(command);
      SavedState saved = SavedState.unchanged().withPayload(SOLO_REASON, check.reason().name());
      return RepairTransitions.openObjection(command.type(), next)
          .map(ack -> saved.withPayload(RepairTransitions.WHILE_OPEN_OBJECTION, ack.name()))
          .orElse(saved);
    }
  }

  private final class Progressing implements StateStore<RepairState> {

    @Override
    public RepairState load(ActionCommand command) {
      return RepairStateStore.this.load(command);
    }

    /**
     * 수리 진행만 바꾼다(하자 인정은 그대로). 종료 확인 요청은 종료를 요청한 측이 연 요청이고, 받는 쪽이 답하면 닫힌다. 판을 보지 않는 행동이라 내용 지문은
     * 없다(안내서 3-3). 보류·이견이 열린 채 시작·종료하면 강행 표시를 payload에 남긴다({@code
     * RepairTransitions.openObjection}). 수리 시작·종료 확인·아직 안 됐어요의 서비스 안 알림은 알리기 PR에서 붙인다.
     */
    @Override
    public SavedState save(ActionCommand command, RepairState next) {
      row(command).changeProgress(next.status(), next.closedBy());
      PartySide side = command.actor().side();
      switch (command.type()) {
        case REQUEST_CLOSE ->
            waits.open(
                new OpenRequestCommand(
                    command.roomRecordId(),
                    RequestKind.CLOSE_CONFIRM,
                    closeRequest(command),
                    side));
        case CONFIRM_CLOSE -> {
          waits.answered(closeRequest(command), RequestKind.CLOSE_CONFIRM, side);
          // 처리 완료(양측 확인)가 되면 내용의 확인 전 고친 내용과 그 확인 요청을 닫는다(06 REP-7l). 지불 고친 내용은 그대로다
          corrections.closeWaiting(
              CorrectionTargetKind.REPAIR_CASE, command.target().id(), ClosedReason.CLOSED_BY_BOTH);
        }
        case NOT_YET -> waits.answered(closeRequest(command), RequestKind.CLOSE_CONFIRM, side);
        default -> {}
      }
      answerProgressCheck(command);
      SavedState saved = SavedState.unchanged();
      return RepairTransitions.openObjection(command.type(), next)
          .map(ack -> saved.withPayload(RepairTransitions.WHILE_OPEN_OBJECTION, ack.name()))
          .orElse(saved);
    }
  }

  private static RequestTarget closeRequest(ActionCommand command) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, command.target().id());
  }

  private final class Supplementing implements StateStore<RepairState> {

    private final String description;
    private final List<String> uploadTickets;

    private Supplementing(String description, List<String> uploadTickets) {
      this.description = description;
      this.uploadTickets = uploadTickets;
    }

    @Override
    public RepairState load(ActionCommand command) {
      return RepairStateStore.this.load(command);
    }

    /**
     * 보충 한 줄을 남기고(그 행이 이 행동의 내용 지문, #155) 하자 인정을 확인 기다림으로 되돌린 뒤 하자 확인 요청을 다시 연다. 새로 열지·묶을지·기한은
     * notification이 정한다({@code WaitService#restart}, 안내서 8-4). 덧붙인 사진은 수리 건에 붙고 지문은 행동 payload
     * {@code photos}로 남는다(#167). 사진만 보충하면 보충 행의 설명은 비어 있다.
     */
    @Override
    public SavedState save(ActionCommand command, RepairState next) {
      row(command).changeDefectAck(next.defectAck());
      PartySide side = command.actor().side();
      DigestInput content =
          supplementWriter.write(command.roomRecordId(), command.target(), side, description);
      waits.restart(
          new OpenRequestCommand(
              command.roomRecordId(), RequestKind.DEFECT_CONFIRM, defectRequest(command), side));
      answerProgressCheck(command);
      return new SavedState(null, content)
          .withPhotos(photos.attach(command, command.target().id(), uploadTickets));
    }
  }

  private final class AskingProgress implements StateStore<RepairState> {

    @Override
    public RepairState load(ActionCommand command) {
      return RepairStateStore.this.load(command);
    }

    /**
     * 상대에게 진행 상황 묻기 요청을 연다(같은 기다림, 03 D). 상대가 먼저 물어 둔 요청이 열려 있으면 그 물음에 대한 답이기도 해서 먼저 닫고(REP-6c) 이
     * 측의 요청을 연다. 이 측이 물어 둔 요청이 아직 열려 있으면 새로 만들지 않고 묶는다({@code WaitService#restart}). 상태 행은 바뀌지 않고
     * 물은 시각은 행동 기록이 원본이다. 판을 보지 않는 행동이라 내용 지문은 없다(안내서 3-3).
     */
    @Override
    public SavedState save(ActionCommand command, RepairState next) {
      answerProgressCheck(command);
      waits.restart(
          new OpenRequestCommand(
              command.roomRecordId(),
              RequestKind.PROGRESS_CHECK,
              RequestTarget.of(TargetKind.REPAIR_CASE, command.target().id()),
              command.actor().side()));
      return SavedState.unchanged();
    }
  }

  /**
   * 진행 상황 남기기의 저장 방법. 만든 진행 상황 ID를 {@link #createdId()}로 돌려준다(같은 키로 다시 온 요청이면 저장하지 않아 비어 있다). 요청마다
   * 새로 만든다.
   */
  public final class NotingProgress implements StateStore<RepairState> {

    private final String text;
    private final List<String> uploadTickets;
    private final boolean asksCheck;
    private UUID createdId;

    private NotingProgress(String text, List<String> uploadTickets, boolean asksCheck) {
      this.text = text;
      this.uploadTickets = uploadTickets;
      this.asksCheck = asksCheck;
    }

    /** 이번 요청이 만든 진행 상황. 저장하지 않았으면(다시 온 요청) 빈 값. */
    public Optional<UUID> createdId() {
      return Optional.ofNullable(createdId);
    }

    /**
     * [상황이 바뀌었어요]를 켰으면 행동하는 측이 지금 요청을 열 수 있는지를 함께 담는다(측마다 달라 이때만 채움). 같은 키로 다시 온 요청은 측을 모르는 채 지나므로
     * 채우지 않는다.
     */
    @Override
    public RepairState load(ActionCommand command) {
      RepairState state = RepairStateStore.this.load(command);
      PartySide side = command.actor().side();
      if (!asksCheck || side == null) {
        return state;
      }
      SituationOpening opening = situationOpening(command.roomRecordId(), side);
      return state.withSituationCheck(state.situationCheck().withOpening(opening));
    }

    /**
     * 진행 상황 한 줄을 남기고(그 줄이 이 행동의 내용 지문, 안내서 3-3) 사진을 그 줄에 붙인다(사진 지문은 행동 payload {@code photos}, 05
     * 2-3). 사진 행이 진행 상황을 가리켜서 줄을 먼저 DB에 내보낸다. 시각은 행동 기록과 같은 {@code at()}이다. 상대가 물어 둔 진행 상황 묻기는 이것으로
     * 닫힌다(REP-6c). [상황이 바뀌었어요]를 켰으면 그 진행 상황을 대상으로 상대에게 상황 변화 확인 요청을 연다(REP-6d). 상대에게 가는 서비스 안
     * 알림·문자는 알리기 PR에서 붙인다.
     */
    @Override
    public SavedState save(ActionCommand command, RepairState next) {
      UUID repairCaseId = command.target().id();
      PartySide side = command.actor().side();
      ProgressNote note =
          notes.saveAndFlush(
              new ProgressNote(
                  command.roomRecordId(),
                  repairCaseId,
                  side,
                  text,
                  null,
                  asksCheck,
                  requestContext.at()));
      createdId = note.getId();
      answerProgressCheck(command);
      if (asksCheck) {
        waits.open(
            new OpenRequestCommand(
                command.roomRecordId(),
                RequestKind.SITUATION_CHECK,
                RequestTarget.of(TargetKind.PROGRESS_NOTE, note.getId()),
                side));
      }
      return new SavedState(null, progressDigest(note))
          .withPhotos(photos.attachToNote(command, repairCaseId, note.getId(), uploadTickets));
    }
  }

  /**
   * 진행 상황의 내용 지문 재료. 진행 상황은 판이 하나라 판 번호는 늘 1이다. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3). {@code
   * asks_check}는 [상황이 바뀌었어요]로 상대에게 확인을 요청한 줄인지라 내용에 넣는다.
   */
  static DigestInput progressDigest(ProgressNote note) {
    return new DigestInput(
        PROGRESS_KIND,
        note.getId(),
        1,
        List.of(
            new DigestField("text", note.getText()),
            new DigestField("asks_check", Boolean.toString(note.isAsksCheck()))));
  }
}
