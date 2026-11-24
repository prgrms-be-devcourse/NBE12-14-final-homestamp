package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.AddPartRequest;
import com.chilsuitjo.back.room.dto.PartChanged;
import com.chilsuitjo.back.room.dto.RoomPartView;
import com.chilsuitjo.back.room.entity.RoomPart;
import com.chilsuitjo.back.room.repository.PartStateStore;
import com.chilsuitjo.back.room.repository.RoomPartRepository;
import com.chilsuitjo.back.room.rule.PartState;
import com.chilsuitjo.back.room.rule.PartTransitions;
import com.chilsuitjo.back.room.rule.PartZone;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 호실 부위(06 ROOM-3, 4-3 부위 API, #178). 부위는 지우지 않고 숨기기만 하고, 숨김과 설비·가전 켜고 끔은 따로다(03 H "부위").
 *
 * <p>상태를 바꾸는 요청은 pipeline이 트랜잭션을 연다(09 3-1). 그래서 여기에 {@code @Transactional}을 두지 않는다. 당사자가 아니면
 * {@code NOT_FOUND}(잠금), 정산 완료·합류 전 기록·내가 공유를 그만둔 호실이면 pipeline이 막는다({@code ActionType}의 허용 표시).
 */
@Service
@RequiredArgsConstructor
public class RoomPartService {

  /** 부위 이름 길이(05 {@code room_part.name} varchar(50)). */
  static final int MAX_NAME = 50;

  private final ActionPipeline pipeline;
  private final PartStateStore store;
  private final PartTransitions rules;
  private final RoomPartRepository parts;
  private final RoomAccessService access;

  /**
   * 부위 목록(숨긴 부위 포함). 구역 순서(V7) 다음 호실 안 순서로 줄 세운다. 숨긴 부위는 {@code hidden}으로 구분하고, 기본 화면 목록에서 빼는 것은
   * 화면이 한다(ROOM-3a). 당사자가 아니면 {@code NOT_FOUND}.
   */
  public List<RoomPartView> list(UUID roomRecordId, UUID memberId) {
    access.require(roomRecordId, memberId);
    return parts.findAllByRoomRecordId(roomRecordId).stream()
        .sorted(
            Comparator.comparingInt((RoomPart part) -> PartZone.rank(part.getZone()))
                .thenComparingInt(RoomPart::getSortOrder)
                .thenComparing(RoomPart::getId))
        .map(this::view)
        .toList();
  }

  /**
   * 부위 추가({@code ADD_PART}). 구역이 V7 6구역이 아니거나 이름이 비었거나 50자를 넘으면 {@code VALIDATION_FAILED}. 같은 이름은
   * 막지 않는다. 같은 키로 다시 오면 처음 만든 부위를 돌려준다.
   */
  public PartChanged add(
      UUID roomRecordId, UUID memberId, String idempotencyKey, AddPartRequest request) {
    String name = request.name().strip();
    if (!PartZone.isKnown(request.zone())
        || name.isEmpty()
        || name.codePointCount(0, name.length()) > MAX_NAME) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    PipelineResult<PartState> result =
        pipeline.create(
            command(roomRecordId, memberId, ActionType.ADD_PART, idempotencyKey, null, Map.of()),
            store.adding(request.zone(), name),
            rules);
    return changed(roomRecordId, result);
  }

  /** 숨기기({@code HIDE_PART}). 이미 숨겼으면 {@code INVALID_STATE}. */
  public PartChanged hide(UUID roomRecordId, UUID partId, UUID memberId, String idempotencyKey) {
    return change(roomRecordId, partId, memberId, ActionType.HIDE_PART, idempotencyKey, Map.of());
  }

  /** 다시 보이기({@code SHOW_PART}, #136). 숨기지 않은 부위면 {@code INVALID_STATE}. */
  public PartChanged show(UUID roomRecordId, UUID partId, UUID memberId, String idempotencyKey) {
    return change(roomRecordId, partId, memberId, ActionType.SHOW_PART, idempotencyKey, Map.of());
  }

  /**
   * 켜고 끄기({@code TOGGLE_PART}). 원하는 값을 받는다. 설비·가전이 아니면 {@code VALIDATION_FAILED}, 이미 그 값이면 {@code
   * INVALID_STATE}. 값은 행동 payload {@code enabled}로 남는다.
   */
  public PartChanged toggle(
      UUID roomRecordId, UUID partId, UUID memberId, String idempotencyKey, boolean enabled) {
    return change(
        roomRecordId,
        partId,
        memberId,
        ActionType.TOGGLE_PART,
        idempotencyKey,
        Map.of("enabled", enabled));
  }

  private PartChanged change(
      UUID roomRecordId,
      UUID partId,
      UUID memberId,
      ActionType type,
      String idempotencyKey,
      Map<String, Object> payload) {
    PipelineResult<PartState> result =
        pipeline.execute(
            command(
                roomRecordId,
                memberId,
                type,
                idempotencyKey,
                ActionTarget.of(TargetKind.ROOM_PART, partId),
                payload),
            store.changing(),
            rules);
    return changed(roomRecordId, result);
  }

  private PartChanged changed(UUID roomRecordId, PipelineResult<PartState> result) {
    UUID partId = result.action().target().id();
    RoomPart part =
        parts
            .findByIdAndRoomRecordId(partId, roomRecordId)
            .orElseThrow(() -> new IllegalStateException("방금 다룬 부위가 없어요: " + partId));
    return new PartChanged(view(part), result.action().replayed());
  }

  private RoomPartView view(RoomPart part) {
    return new RoomPartView(
        part.getId(),
        part.getZone(),
        part.getName(),
        part.isEnabled(),
        part.isHidden(),
        store.toggleable(part));
  }

  private static ActionCommand command(
      UUID roomRecordId,
      UUID memberId,
      ActionType type,
      String idempotencyKey,
      ActionTarget target,
      Map<String, Object> payload) {
    return new ActionCommand(
        roomRecordId,
        Actor.member(memberId),
        type,
        idempotencyKey,
        target,
        null,
        null,
        null,
        payload);
  }
}
