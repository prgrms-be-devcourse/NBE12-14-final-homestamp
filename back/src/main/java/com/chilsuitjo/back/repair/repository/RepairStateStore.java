package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수리 건 상태 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * RepairTransitions}에만).
 *
 * <p>제목·설명 같은 내용은 행동 payload에 넣지 않고 판({@code repair_content})에만 둔다(05 2-3 "payload는 작은 부가 값만", 04
 * "한 사실은 한 곳에"). 그래서 하자 등록은 요청마다 내용을 담은 저장 방법을 {@link #registering}으로 만들어 pipeline에 넘긴다.
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

  /** 하자 등록(06 REP-1)의 저장 방법. 같은 키로 다시 온 요청이면 pipeline이 만든 수리 건을 {@code load}로 다시 읽는다. */
  public StateStore<RepairState> registering(UUID roomPartId, String title, String description) {
    return new Registering(
        Objects.requireNonNull(roomPartId, "roomPartId"),
        Objects.requireNonNull(title, "title"),
        description);
  }

  /** 지금 상태. 대상이 없으면(만드는 행동) 빈 상태다. 다른 호실의 수리 건 ID는 {@code NOT_FOUND}(ROOM-2a). */
  RepairState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target == null) {
      return RepairState.NONE;
    }
    if (target.kind() != TargetKind.REPAIR_CASE) {
      throw new IllegalArgumentException("수리 건이 아닌 대상이에요: " + target);
    }
    RepairCase row =
        cases
            .findByIdAndRoomRecordId(target.id(), command.roomRecordId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return new RepairState(
        row.getStatus(), row.getDefectAck(), row.getMoneyStatus(), row.getCreatedBySide());
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

  private final class Registering implements StateStore<RepairState> {

    private final UUID roomPartId;
    private final String title;
    private final String description;

    private Registering(UUID roomPartId, String title, String description) {
      this.roomPartId = roomPartId;
      this.title = title;
      this.description = description;
    }

    @Override
    public RepairState load(ActionCommand command) {
      return RepairStateStore.this.load(command);
    }

    /**
     * 머리와 판 #1을 한 트랜잭션에 만든다. 서로를 가리켜서 두 ID를 저장 전에 만든다(05 0장 "머리·판 네 쌍", 지연 FK). 시각은 행동 기록과 같은
     * {@code at()}이다.
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
      contents.save(
          new RepairContent(
              contentId, roomRecordId, caseId, 1, roomPartId, title, description, null, side, at));

      return new SavedState(ActionTarget.repairCase(caseId), digest(contentId));
    }

    /** 판 #1의 내용 지문 재료. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3, 테스트로 고정). */
    private DigestInput digest(UUID contentId) {
      return new DigestInput(
          CONTENT_KIND,
          contentId,
          1,
          List.of(
              new DigestField("room_part_id", roomPartId.toString()),
              new DigestField("title", title),
              new DigestField("description", description)),
          List.of());
    }
  }
}
