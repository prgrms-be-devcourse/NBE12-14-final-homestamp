package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.entity.PartTemplate;
import com.chilsuitjo.back.room.entity.RoomPart;
import com.chilsuitjo.back.room.rule.PartState;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 호실 부위 상태 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * PartTransitions}에만).
 *
 * <p>부위 행동은 판을 보지 않아 내용 지문이 없다(decisions 2026-11-19 "판을 보지 않는 행동은 NULL"). 구역·이름은 부위 행에만 두고 행동
 * payload에 복사하지 않는다(05 2-3 "payload는 작은 부가 값만").
 */
@Component
@RequiredArgsConstructor
public class PartStateStore {

  private final RoomPartRepository parts;
  private final PartTemplateRepository templates;
  private final ActionRequestContext requestContext;

  /** 켜고 끌 수 있는 템플릿 ID. 처음 쓸 때 채운다(고정 데이터). */
  private volatile Set<UUID> optionalTemplateIds;

  /** 부위 추가({@code ADD_PART})의 저장 방법. 구역·이름은 서비스가 먼저 검사한 값이다. */
  public StateStore<PartState> adding(String zone, String name) {
    return new Adding(Objects.requireNonNull(zone), Objects.requireNonNull(name));
  }

  /** 숨김·다시 보이기·켜고 끔({@code HIDE_PART}·{@code SHOW_PART}·{@code TOGGLE_PART})의 저장 방법. */
  public StateStore<PartState> changing() {
    return new Changing();
  }

  /**
   * 이 부위를 켜고 끌 수 있나: 기본 목록에서 복사한 설비·가전({@code part_template.optional})만. 직접 더한 부위는 아니다. 기본 목록은
   * Flyway가 넣고 앱은 읽기만 하는 고정 데이터라 처음 한 번 읽어 둔다(목록 조회에서 부위마다 다시 읽지 않게).
   */
  public boolean toggleable(RoomPart part) {
    return part.getTemplateId() != null && optionalTemplates().contains(part.getTemplateId());
  }

  private Set<UUID> optionalTemplates() {
    Set<UUID> ids = optionalTemplateIds;
    if (ids == null) {
      ids =
          templates.findAll().stream()
              .filter(PartTemplate::isOptional)
              .map(PartTemplate::getId)
              .collect(Collectors.toUnmodifiableSet());
      optionalTemplateIds = ids;
    }
    return ids;
  }

  /** 지금 상태. 대상이 없으면(더하기 전) 빈 상태다. 다른 호실의 부위 ID는 {@code NOT_FOUND}(ROOM-2a). */
  private PartState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target == null) {
      return PartState.NONE;
    }
    if (target.kind() != TargetKind.ROOM_PART) {
      throw new IllegalArgumentException("부위가 아닌 대상이에요: " + target);
    }
    RoomPart part = row(command);
    return new PartState(true, toggleable(part), part.isEnabled(), part.isHidden());
  }

  private RoomPart row(ActionCommand command) {
    return parts
        .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private final class Adding implements StateStore<PartState> {

    private final String zone;
    private final String name;

    private Adding(String zone, String name) {
      this.zone = zone;
      this.name = name;
    }

    @Override
    public PartState load(ActionCommand command) {
      return PartStateStore.this.load(command);
    }

    /**
     * 호실 안 순서의 맨 뒤에 둔다. 화면은 구역 순서 다음 이 순서로 줄 세우므로 그 구역의 맨 뒤에 보인다. 행동 기록이 부위를 대상으로 삼기 전에 행을 DB에
     * 내보낸다(대상 칸 FK). 시각은 행동 기록과 같은 {@code at()}이다.
     */
    @Override
    public SavedState save(ActionCommand command, PartState next) {
      UUID roomRecordId = command.roomRecordId();
      int sortOrder = parts.maxSortOrder(roomRecordId) + 1;
      RoomPart part =
          parts.save(RoomPart.added(roomRecordId, zone, name, sortOrder, requestContext.at()));
      parts.flush();
      return SavedState.created(ActionTarget.of(TargetKind.ROOM_PART, part.getId()));
    }
  }

  private final class Changing implements StateStore<PartState> {

    @Override
    public PartState load(ActionCommand command) {
      return PartStateStore.this.load(command);
    }

    @Override
    public SavedState save(ActionCommand command, PartState next) {
      RoomPart part = row(command);
      if (next.hidden() != part.isHidden()) {
        if (next.hidden()) {
          part.hide(requestContext.at());
        } else {
          part.show();
        }
      }
      if (next.enabled() != part.isEnabled()) {
        part.changeEnabled(next.enabled());
      }
      return SavedState.unchanged();
    }
  }
}
