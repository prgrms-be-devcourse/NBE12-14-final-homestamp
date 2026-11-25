package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.repair.dto.RepairCaseDetail;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.repository.RepairContentRepository;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
          ActionType.ASK_PROGRESS, "ask-progress",
          ActionType.NOTE_PROGRESS, "progress-notes");

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final RepairStateStore store;
  private final RepairTransitions rules;
  private final RoomGateReader gates;
  private final RoomQueryService rooms;
  private final ActionReader actionReader;
  private final PhotoService photos;
  private final RoomAccessService access;
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
   * 없고, 남은 쪽의 지금 상태에는 그만둔 쪽이 바꾼 것이 없다(하자 응답·보충은 공유 중단 중 막힘).
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
    Instant asOf = store.sharingCutoff(row, gate.side()).orElse(null);
    RepairState shown = asOf == null ? state : visibleState(row, gate.side(), asOf);
    RepairContent content =
        contents
            .findByRepairCaseIdAndNo(row.getId(), shown.contentNo())
            .orElseThrow(() -> new IllegalStateException("보이는 판이 없어요: " + row.getId()));
    String partName = rooms.findPart(roomRecordId, content.getRoomPartId()).name();
    List<String> actions =
        rules.available(state, gate.side(), clock.instant()).stream()
            .filter(type -> gate.rejection(type).isEmpty())
            .map(ACTION_NAMES::get)
            .toList();
    PhotoTarget photoTarget = new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, row.getId());
    List<PhotoCard> photoCards =
        photos.cards(access.require(roomRecordId, memberId), List.of(photoTarget)).get(photoTarget);
    return new RepairCaseDetail(
        row.getId(),
        roomRecordId,
        gate.side(),
        row.getCreatedBySide(),
        shown.status(),
        shown.closeRequestedBy(),
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
        new RepairCaseDetail.Money(shown.moneyStatus()),
        photoCards,
        actions);
  }

  /**
   * 보는 사람에게 보이는 행동만으로 다시 계산한 상태(03 6-6 9번). 고정 시각까지의 행동(그 시각에 남은 것 포함, 불변식 18 "뒤에 남긴 것은 ✕")과 보는 사람
   * 측의 행동을 남긴다. 행동 기록이 원본이라 같은 규칙({@link RepairTransitions})으로 다시 계산하면 그때의 상태가 나온다(REP-10).
   */
  private RepairState visibleState(RepairCase row, PartySide viewer, Instant asOf) {
    List<ActionEntry> visible =
        actionReader.findByTarget(ActionTarget.repairCase(row.getId())).stream()
            .filter(entry -> !entry.createdAt().isAfter(asOf) || entry.actorSide() == viewer)
            .toList();
    return Replay.fold(rules, visible);
  }
}
