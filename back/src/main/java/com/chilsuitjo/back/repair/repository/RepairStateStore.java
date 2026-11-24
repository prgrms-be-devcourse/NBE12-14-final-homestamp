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
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Instant;
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

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final RoomQueryService rooms;
  private final CounterpartReader parties;
  private final ActionRequestContext requestContext;
  private final ActionReader actions;
  private final WaitService waits;
  private final SupplementWriter supplementWriter;
  private final RepairPhotos photos;

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
    RepairCase row = row(command);
    int contentNo =
        contents
            .findById(row.getCurrentContentId())
            .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + row.getId()))
            .getNo();
    Instant lastSupplementAt =
        actions.findByTarget(target).stream()
            .filter(entry -> entry.type() == ActionType.SUPPLEMENT)
            .map(ActionEntry::createdAt)
            .max(Instant::compareTo)
            .orElse(null);
    RoomParties room = parties.read(row.getRoomRecordId(), row.getCreatedBySide());
    return new RepairState(
        row.getStatus(),
        row.getDefectAck(),
        row.getMoneyStatus(),
        row.getCreatedBySide(),
        contentNo,
        lastSupplementAt,
        canOpenDefectConfirm(room, command.roomRecordId()),
        hiddenFromCounterpart(room, row));
  }

  /**
   * 지금 하자 확인 요청을 열 수 있나(03 K "거주 종료 뒤", 1-4). 호실 단계는 {@code CounterpartReader}가 실제 호실 상태로
   * 돌려주고({@code RoomParties.canOpen} = {@code RequestOpening}), 그 위에 notification이 막아 둔 것도 함께
   * 본다({@code WaitService.canOpen}). 지금 {@code FakeWaitService}는 호실 단계를 보지 않아서, 호실 단계를 여기서도 봐야 거주
   * 종료 뒤 보충이 막힌다(#157 리뷰).
   */
  private boolean canOpenDefectConfirm(RoomParties room, UUID roomRecordId) {
    return room.canOpen(RequestKind.DEFECT_CONFIRM)
        && waits.canOpen(roomRecordId, RequestKind.DEFECT_CONFIRM);
  }

  /**
   * 상대 측이 이 기록을 볼 수 없나(03 불변식 18): 제한이 살아 있는 공유 중단 시각 중 이른 것보다 뒤에 등록한 기록이면 참이다. 공유 중단은 양방향이라 어느 쪽이
   * 그만뒀든 같다. 상대가 탈퇴해 풀린 공유 중단은 보지 않는다({@code RoomParties}의 판단, 06 CONS-3p·3q). 연결 전에 남긴 기록은 연결하면
   * 보이므로(03 1-4) 연결 여부는 보지 않는다.
   */
  private static boolean hiddenFromCounterpart(RoomParties room, RepairCase row) {
    Instant stopped = null;
    if (room.mySharingStoppedApplies()) {
      stopped = room.mine().sharingStoppedAt();
    }
    if (room.counterpartSharingStoppedApplies()) {
      Instant theirs = room.counterpart().sharingStoppedAt();
      stopped = stopped == null || theirs.isBefore(stopped) ? theirs : stopped;
    }
    return stopped != null && row.getCreatedAt().isAfter(stopped);
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

  /** 판의 내용 지문 재료. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3, 테스트로 고정). */
  private static DigestInput digest(RepairContent content) {
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
      if (rooms.findPart(roomRecordId, roomPartId).hidden()) {
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
      return SavedState.unchanged();
    }
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
      return new SavedState(null, content)
          .withPhotos(photos.attach(command, command.target().id(), uploadTickets));
    }
  }
}
