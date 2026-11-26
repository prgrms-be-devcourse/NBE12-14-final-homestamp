package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import com.chilsuitjo.back.inspection.rule.InspectionItemState;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 점검 항목 상태 행을 읽고 쓴다({@code StateStore}). 규칙은 검사하지 않는다(규칙은 {@code InspectionItemTransitions}에만).
 *
 * <p>부위·설명은 행동 payload에 넣지 않고 판({@code inspection_item_version})에만 둔다(05 2-3, 04 "한 사실은 한 곳에").
 */
@Component
@RequiredArgsConstructor
public class InspectionItemStateStore {

  /** 판 내용 지문의 판 종류(05 2-3 「해시 입력 형식」 내용 지문). */
  static final String CONTENT_KIND = "inspection_item_version";

  /** 항목 더하기 payload의 부모 점검 키({@code AddItemPayload.inspectionId}). */
  public static final String INSPECTION_ID = "inspectionId";

  private final InspectionRepository inspections;
  private final InspectionItemRepository items;
  private final InspectionItemVersionRepository versions;
  private final RoomQueryService rooms;
  private final ActionRequestContext requestContext;
  private final InspectionPhotos photos;

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

  /**
   * 지금 상태. 같은 키로 다시 온 요청이면 처음 만든 항목을 읽는다. 처음 요청이면 payload의 점검을 읽어 그 상태만 담는다. 다른 호실의 점검·항목 ID는
   * {@code NOT_FOUND}다(ROOM-2a).
   */
  InspectionItemState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target == null) {
      Inspection parent = inspection(command, parentId(command));
      return InspectionItemState.newIn(parent.getStatus(), parent.getAuthorSide());
    }
    if (target.kind() != TargetKind.INSPECTION_ITEM) {
      throw new IllegalArgumentException("점검 항목이 아닌 대상이에요: " + target);
    }
    InspectionItem row =
        items
            .findByIdAndRoomRecordId(target.id(), command.roomRecordId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    Inspection parent = inspection(command, row.getInspectionId());
    InspectionItemVersion current =
        versions
            .findById(row.getCurrentVersionId())
            .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + row.getId()));
    return new InspectionItemState(
        parent.getStatus(),
        parent.getAuthorSide(),
        row.getStatus(),
        row.getAddedBySide(),
        current.getNo());
  }

  private Inspection inspection(ActionCommand command, UUID inspectionId) {
    return inspections
        .findByIdAndRoomRecordId(inspectionId, command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static UUID parentId(ActionCommand command) {
    Object id = command.payload().get(INSPECTION_ID);
    if (id == null) {
      throw new IllegalArgumentException("payload에 점검 ID가 없어요");
    }
    return UUID.fromString(id.toString());
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
     * <p>작성 중 기준 측이 더한 항목이라 잠긴 뒤·거주 종료 뒤 추가가 아니다. 그 표시는 잠긴 뒤 추가(INSP-1e)·퇴거 점검(INSP-3a)에서 서버가 정한다.
     */
    @Override
    public SavedState save(ActionCommand command, InspectionItemState next) {
      UUID roomRecordId = command.roomRecordId();
      // 남의 부위·없는 부위는 NOT_FOUND(RoomQueryService). 숨긴 부위는 새 기록을 받지 않는다(06 ROOM-3a).
      if (rooms.findPart(roomRecordId, roomPartId).hidden()) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      Inspection parent = inspection(command, parentId(command));
      Instant at = requestContext.at();
      UUID itemId = Uuids.v7();
      UUID versionId = Uuids.v7();

      items.save(
          new InspectionItem(
              itemId, roomRecordId, parent.getId(), versionId, next.addedBy(), false, false, at));
      InspectionItemVersion first =
          new InspectionItemVersion(
              versionId, roomRecordId, itemId, 1, roomPartId, description, null, at);
      versions.save(first);
      items.flush();

      return new SavedState(ActionTarget.of(TargetKind.INSPECTION_ITEM, itemId), digest(first))
          .withPhotos(
              photos.attach(command, parent.getId(), parent.getKind(), itemId, uploadTickets));
    }
  }
}
