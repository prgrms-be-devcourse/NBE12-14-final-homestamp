package com.chilsuitjo.back.inspection.repository;

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
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import com.chilsuitjo.back.inspection.rule.ConfirmAllState;
import com.chilsuitjo.back.inspection.rule.InspectionItemState;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 점검 항목 상태 행을 읽고 쓴다({@code StateStore}). 규칙은 검사하지 않는다(규칙은 {@code InspectionItemTransitions}·{@code
 * ConfirmAllTransitions}에만).
 *
 * <p>부위·설명은 행동 payload에 넣지 않고 판({@code inspection_item_version})에만 둔다(05 2-3, 04 "한 사실은 한 곳에").
 *
 * <p><b>점검 확인 요청</b>(안내서 8-4): 요청은 점검 × 방향마다 하나이고 요청한 측은 그 항목을 더한 측이다. 그 방향의 미응답 항목(확인 기다림·확인 없음)이
 * 하나도 남지 않으면 답한 것으로 닫고(보류·이견도 답이다), 보충하면 다시 연다(#235).
 */
@Component
@RequiredArgsConstructor
public class InspectionItemStateStore {

  /** 판 내용 지문의 판 종류(05 2-3 「해시 입력 형식」 내용 지문). */
  static final String CONTENT_KIND = "inspection_item_version";

  /** 항목 더하기 payload의 부모 점검 키({@code AddItemPayload.inspectionId}). */
  public static final String INSPECTION_ID = "inspectionId";

  /** 아직 답하지 않은 항목 응답. 모두 확인의 대상이고, 남아 있으면 그 방향의 점검 확인 요청이 열려 있다. */
  private static final List<AckState> UNANSWERED = List.of(AckState.PENDING, AckState.UNCONFIRMED);

  private final InspectionRepository inspections;
  private final InspectionItemRepository items;
  private final InspectionItemVersionRepository versions;
  private final RoomQueryService rooms;
  private final ActionRequestContext requestContext;
  private final InspectionPhotos photos;
  private final InspectionSight sight;
  private final ActionReader actions;
  private final CounterpartReader parties;
  private final WaitService waits;
  private final SupplementWriter supplementWriter;

  /**
   * 항목 더하기(03 H "항목")의 저장 방법.
   *
   * @param uploadTickets 붙일 사진의 업로드 표 0~10개(장수는 요청 본문이 먼저 본다)
   */
  public StateStore<InspectionItemState> adding(
      UUID roomPartId, String description, List<String> uploadTickets) {
    return new Adding(
        Objects.requireNonNull(roomPartId, "roomPartId"),
        Objects.requireNonNull(description, "description"),
        List.copyOf(uploadTickets));
  }

  /** 항목 응답(06 4-7 {@code respond})의 저장 방법. 모두 확인의 각 항목 단계도 이것을 쓴다. */
  public StateStore<InspectionItemState> responding() {
    return new Responding();
  }

  /**
   * 보충하고 다시 확인 요청(06 4-7 {@code supplement})의 저장 방법.
   *
   * @param description 덧붙인 설명. 사진만 보충했으면 null
   * @param uploadTickets 덧붙인 사진의 업로드 표. 설명만 보충했으면 빈 목록
   */
  public StateStore<InspectionItemState> supplementing(
      String description, List<String> uploadTickets) {
    return new Supplementing(description, List.copyOf(uploadTickets));
  }

  /** 남은 항목 모두 확인(06 4-7 {@code confirm-all})의 첫 단계({@code CONFIRM_ALL}) 저장 방법. */
  public StateStore<ConfirmAllState> confirmingAll() {
    return new ConfirmingAll();
  }

  /**
   * 응답할 때 본 판의 내용 지문 재료(본 판에 대고 하는 행동은 그 판, 안내서 3-3). 판은 고칠 수 없어서(B) pipeline 잠금 전에 읽어도 값이 같다. 그런
   * 판이 없으면 빈 값이고, 그때는 {@code apply}가 판 번호가 달라 {@code VERSION_CONFLICT}로 막는다.
   */
  public Optional<DigestInput> seenContent(UUID itemId, Integer seenVersionNo) {
    if (seenVersionNo == null) {
      return Optional.empty();
    }
    return versions
        .findByInspectionItemIdAndNo(itemId, seenVersionNo)
        .map(InspectionItemStateStore::digest);
  }

  /**
   * 지금 상태. 같은 키로 다시 온 요청이면 처음 만든 항목을 읽는다. 처음 요청이면 payload의 점검을 읽어 그 상태만 담는다. 다른 호실의 점검·항목 ID는
   * {@code NOT_FOUND}다(ROOM-2a).
   */
  InspectionItemState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target == null) {
      Inspection parent = inspection(command.roomRecordId(), parentId(command));
      return InspectionItemState.newIn(parent.getStatus(), parent.getAuthorSide());
    }
    if (target.kind() != TargetKind.INSPECTION_ITEM) {
      throw new IllegalArgumentException("점검 항목이 아닌 대상이에요: " + target);
    }
    InspectionItem row = item(command);
    Inspection parent = inspection(command.roomRecordId(), row.getInspectionId());
    return current(parent, row, command.actor().side());
  }

  /**
   * 이 항목 행의 지금 상태({@link #load}와 같은 값). 상세 조회가 {@code availableActions}를 계산할 때도 쓴다(06 4-0 "서버의 같은
   * 함수"). 행동하는 측을 모르면(같은 키 재요청의 다시 읽기) 보이는지는 null이다. 트랜잭션 안에서 부른다(행동 기록 읽기).
   */
  public InspectionItemState current(Inspection parent, InspectionItem row, PartySide actor) {
    return current(parent, row, actor, actor == null ? null : sight.of(parent, actor));
  }

  /**
   * {@link #current(Inspection, InspectionItem, PartySide)}와 같은 값이고, 이 사람의 보이는 범위를 이미 구했으면 넘겨 다시 읽지
   * 않는다(상세 조회가 항목마다 부를 때, #272 리뷰).
   *
   * @param seen {@code sight.of(parent, actor)}의 결과. 행동하는 측을 모르면 null
   */
  public InspectionItemState current(
      Inspection parent, InspectionItem row, PartySide actor, InspectionSight.Sight seen) {
    InspectionItemVersion version =
        versions
            .findById(row.getCurrentVersionId())
            .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + row.getId()));
    Boolean hidden = seen == null ? null : !seen.sees(row);
    return new InspectionItemState(
        parent.getStatus(),
        parent.getAuthorSide(),
        row.getStatus(),
        row.getAddedBySide(),
        version.getNo(),
        lastSupplementAt(row.getId()),
        sight.canOpenConfirm(row.getRoomRecordId(), row.getAddedBySide()),
        hidden);
  }

  /**
   * 모두 확인을 판단할 상태. 이 사람에게 보이는 항목 가운데 상대가 더했고 아직 답하지 않은 것(확인 기다림·상대 미확인)이 대상이다. 상세 조회의 {@code
   * confirm-all} 버튼도 같은 값을 쓴다. "항목이 없는 점검"은 보이는 항목이 아니라 상대가 더한 항목 전체로 본다(#272·#291 리뷰, {@link
   * ConfirmAllState#noItems}).
   */
  public ConfirmAllState confirmAll(Inspection row, PartySide actor) {
    return confirmAll(row, actor, sight.of(row, actor));
  }

  /**
   * {@link #confirmAll(Inspection, PartySide)}와 같고, 이미 구한 보이는 범위({@code sight.of(row, actor)})를 쓴다.
   */
  public ConfirmAllState confirmAll(Inspection row, PartySide actor, InspectionSight.Sight seen) {
    List<InspectionItem> all = items.findByInspectionIdOrderByIdAsc(row.getId());
    List<InspectionItem> shown = all.stream().filter(seen::sees).toList();
    Map<UUID, Integer> unanswered = new LinkedHashMap<>();
    for (InspectionItem item : shown) {
      if (item.getAddedBySide() != actor && UNANSWERED.contains(item.getStatus())) {
        unanswered.put(item.getId(), versionNo(item));
      }
    }
    boolean confirmedNoItems =
        actions.findByTarget(ActionTarget.inspection(row.getId())).stream()
            .anyMatch(e -> e.type() == ActionType.CONFIRM_ALL && e.actorSide() == actor);
    int othersItems = (int) all.stream().filter(item -> item.getAddedBySide() != actor).count();
    return new ConfirmAllState(
        row.getStatus(),
        row.getAuthorSide(),
        unanswered,
        othersItems,
        seen.draftHidden(),
        confirmedNoItems);
  }

  private int versionNo(InspectionItem item) {
    return versions
        .findById(item.getCurrentVersionId())
        .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + item.getId()))
        .getNo();
  }

  /** 마지막 보충 시각(행동 기록이 원본, 안내서 8-1). 없으면 null. */
  private Instant lastSupplementAt(UUID itemId) {
    return actions.findByTarget(ActionTarget.of(TargetKind.INSPECTION_ITEM, itemId)).stream()
        .filter(entry -> entry.type() == ActionType.SUPPLEMENT_ITEM)
        .map(ActionEntry::createdAt)
        .max(Instant::compareTo)
        .orElse(null);
  }

  private InspectionItem item(ActionCommand command) {
    return items
        .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private Inspection inspection(UUID roomRecordId, UUID inspectionId) {
    return inspections
        .findByIdAndRoomRecordId(inspectionId, roomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static UUID parentId(ActionCommand command) {
    Object id = command.payload().get(INSPECTION_ID);
    if (id == null) {
      throw new IllegalArgumentException("payload에 점검 ID가 없어요");
    }
    return UUID.fromString(id.toString());
  }

  /**
   * 그 방향(요청한 측 = 항목을 더한 측)에 미응답 항목이 하나도 없으면 점검 확인 요청을 "답함"으로 닫는다. 열린 요청이 없으면(요청을 열지 않은 경우·늦은 답)
   * notification이 아무것도 하지 않는다({@code WaitService#answered}).
   */
  private void answerIfSettled(UUID inspectionId, PartySide requestedBy, PartySide answeredBy) {
    boolean waiting =
        items.findByInspectionIdOrderByIdAsc(inspectionId).stream()
            .anyMatch(item ->
                item.getAddedBySide() == requestedBy && UNANSWERED.contains(item.getStatus()));
    if (!waiting) {
      waits.answered(confirmRequest(inspectionId), RequestKind.INSPECTION_CONFIRM, answeredBy);
    }
  }

  private static RequestTarget confirmRequest(UUID inspectionId) {
    return RequestTarget.of(TargetKind.INSPECTION, inspectionId);
  }

  /** 판의 내용 지문 재료. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3, 테스트로 고정). */
  static DigestInput digest(InspectionItemVersion version) {
    return new DigestInput(
        CONTENT_KIND,
        version.getId(),
        version.getNo(),
        List.of(
            new DigestField("room_part_id", version.getRoomPartId().toString()),
            new DigestField("description", version.getDescription())));
  }

  private final class Adding implements StateStore<InspectionItemState> {

    private final UUID roomPartId;
    private final String description;
    private final List<String> uploadTickets;

    private Adding(UUID roomPartId, String description, List<String> uploadTickets) {
      this.roomPartId = roomPartId;
      this.description = description;
      this.uploadTickets = uploadTickets;
    }

    @Override
    public InspectionItemState load(ActionCommand command) {
      return InspectionItemStateStore.this.load(command);
    }

    /**
     * 머리와 판 #1을 한 트랜잭션에 만든다(05 0장 "머리·판 네 쌍", 지연 FK). 내용 지문은 새로 만든 판 #1이다(안내서 3-3). 사진은 머리·판을 DB에
     * 내보낸 뒤 항목에 붙이고, 지문은 행동 payload {@code photos}로 남는다. 사진이 실패하면 항목도 함께 되돌려진다.
     *
     * <p>표시는 서버가 정한다(06 4-7 {@code items}): 점검이 잠겼으면 "잠긴 뒤 추가"({@code added_after_lock}, INSP-1e),
     * 거주가 끝났으면 "거주 종료 뒤 추가"({@code added_after_end}, 03 H). 점검 상태는 바꾸지 않는다(잠금 그대로).
     *
     * <p>점검 확인 요청(요청한 측 = 더한 측)은 같은 트랜잭션에서 연다. 같은 방향의 요청이 열려 있으면 그것에 묶인다({@code WaitService#open}).
     * 작성 중 기준 측이 쓴 항목은 확인 요청을 보낼 때 함께 묻으므로 열지 않는다(03 H). 상대가 공유를 그만뒀거나 받을 상대가 없으면 열지 않는다(#234와 같은
     * 기준 {@code InspectionSight.reachable}, 03 B·불변식 18). 서비스 안 알림은 #116(U3-2) 뒤에 붙인다.
     */
    @Override
    public SavedState save(ActionCommand command, InspectionItemState next) {
      UUID roomRecordId = command.roomRecordId();
      // 남의 부위·없는 부위는 NOT_FOUND(RoomQueryService). 숨긴 부위와 꺼 둔 설비·가전은 새 기록을 받지 않는다(06 ROOM-3a·3d,
      // decisions 2026-11-26 #182). 공유를 그만둔 뒤에는 더하는 사람에게 보이는 부위 상태로 본다(수리 건 등록과 같음, 불변식 18)
      RoomPartSummary seenPart =
          rooms.findPartAsSeenBy(roomRecordId, roomPartId, command.actor().memberId());
      if (seenPart.hidden() || !seenPart.enabled()) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      Inspection parent = inspection(roomRecordId, parentId(command));
      Instant at = requestContext.at();
      UUID itemId = Uuids.v7();
      UUID versionId = Uuids.v7();

      RoomParties room = parties.read(roomRecordId, next.addedBy());
      boolean afterLock = parent.getStatus() == InspectionStatus.LOCKED;
      boolean afterEnd = room.phase() != RoomPhase.LIVING;
      items.save(
          new InspectionItem(
              itemId,
              roomRecordId,
              parent.getId(),
              versionId,
              next.addedBy(),
              afterLock,
              afterEnd,
              at));
      InspectionItemVersion first =
          new InspectionItemVersion(
              versionId, roomRecordId, itemId, 1, roomPartId, description, null, at);
      versions.save(first);
      items.flush();
      boolean authorDraft =
          parent.getStatus() == InspectionStatus.DRAFT && next.addedBy() == parent.getAuthorSide();
      if (!authorDraft
          && InspectionSight.reachable(room)
          && sight.canOpenConfirm(room, roomRecordId)) {
        waits.open(
            new OpenRequestCommand(
                roomRecordId,
                RequestKind.INSPECTION_CONFIRM,
                confirmRequest(parent.getId()),
                next.addedBy()));
      }

      // 작성 중 기준 측 항목의 사진은 항목과 함께 확인 요청 때부터 상대에게 보인다(03 3-1 "작성 중 점검", #295)
      return new SavedState(ActionTarget.of(TargetKind.INSPECTION_ITEM, itemId), digest(first))
          .withPhotos(
              photos.attach(
                  command, parent.getId(), parent.getKind(), itemId, uploadTickets, !authorDraft));
    }
  }

  private final class Responding implements StateStore<InspectionItemState> {

    @Override
    public InspectionItemState load(ActionCommand command) {
      return InspectionItemStateStore.this.load(command);
    }

    /**
     * 항목 응답을 바꾸고 답한 판에 도장을 찍는다(03 1-3 "[맞아요]·보류·이견 모두 도장"). 그 방향의 미응답 항목이 남지 않으면 점검 확인 요청을 닫는다. 내용
     * 지문은 서비스가 넘긴 본 판이다. 항목을 더한 측에게 가는 서비스 안 알림은 알리기 작업(#116)에서 붙인다.
     */
    @Override
    public SavedState save(ActionCommand command, InspectionItemState next) {
      InspectionItem row = item(command);
      row.changeStatus(next.status());
      row.stampCurrent();
      items.flush();
      answerIfSettled(row.getInspectionId(), row.getAddedBySide(), command.actor().side());
      return SavedState.unchanged();
    }
  }

  private final class Supplementing implements StateStore<InspectionItemState> {

    private final String description;
    private final List<String> uploadTickets;

    private Supplementing(String description, List<String> uploadTickets) {
      this.description = description;
      this.uploadTickets = uploadTickets;
    }

    @Override
    public InspectionItemState load(ActionCommand command) {
      return InspectionItemStateStore.this.load(command);
    }

    /**
     * 보충 한 줄을 남기고(그 행이 이 행동의 내용 지문, #155) 항목을 확인 기다림으로 되돌린 뒤 그 방향의 점검 확인 요청을 다시 연다(새로 열지·묶을지는
     * notification이 정한다, {@code WaitService#restart}). 받을 상대가 없거나 공유 중단 제한이 살아 있으면 요청은 열지 않는다(#218과
     * 같은 기준, #163 6-1). 덧붙인 사진은 항목에 붙고 지문은 행동 payload {@code photos}로 남는다.
     */
    @Override
    public SavedState save(ActionCommand command, InspectionItemState next) {
      InspectionItem row = item(command);
      row.changeStatus(next.status());
      PartySide side = command.actor().side();
      UUID roomRecordId = command.roomRecordId();
      DigestInput content =
          supplementWriter.write(roomRecordId, command.target(), side, description);
      if (InspectionSight.reachable(parties.read(roomRecordId, side))) {
        waits.restart(
            new OpenRequestCommand(
                roomRecordId,
                RequestKind.INSPECTION_CONFIRM,
                confirmRequest(row.getInspectionId()),
                side));
      }
      Inspection parent = inspection(roomRecordId, row.getInspectionId());
      // 보충은 확인 요청 중에만 하므로 사진은 붙인 때부터 상대에게 보인다
      return new SavedState(null, content)
          .withPhotos(
              photos.attach(
                  command, parent.getId(), parent.getKind(), row.getId(), uploadTickets, true));
    }
  }

  private final class ConfirmingAll implements StateStore<ConfirmAllState> {

    @Override
    public ConfirmAllState load(ActionCommand command) {
      ActionTarget target = command.target();
      if (target == null || target.kind() != TargetKind.INSPECTION) {
        throw new IllegalArgumentException("점검이 아닌 대상이에요: " + target);
      }
      Inspection row = inspection(command.roomRecordId(), target.id());
      PartySide side = command.actor().side();
      if (side == null) {
        // 같은 키로 다시 온 요청의 다시 읽기(측을 모름). 응답에는 점검 상태만 쓴다
        return new ConfirmAllState(row.getStatus(), row.getAuthorSide(), Map.of(), 0, false, false);
      }
      return confirmAll(row, side);
    }

    /**
     * 행동만 남긴다(점검 상태는 그대로). 각 항목의 맞아요는 뒤따르는 {@code RESPOND_ITEM} 단계가 남기고 요청도 거기서 닫힌다. 항목이 없는 점검이면
     * 여기서 "하자 기록 없음" 확인으로 점검 확인 요청을 닫는다(03 H).
     */
    @Override
    public SavedState save(ActionCommand command, ConfirmAllState next) {
      if (next.noItems()) {
        waits.answered(
            confirmRequest(command.target().id()),
            RequestKind.INSPECTION_CONFIRM,
            command.actor().side());
      }
      return SavedState.unchanged();
    }
  }
}
