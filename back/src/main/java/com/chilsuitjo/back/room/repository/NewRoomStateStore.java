package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomPart;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.rule.RoomState;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 호실 기록 만들기({@code CREATE_ROOM}) 한 요청의 저장. 요청 값(주소·별칭·입주일)을 들고 있어서 요청마다 하나 만든다.
 *
 * <p>{@link #save}는 호실 기록, 두 당사자 자리(만든 회원의 자리와 반대쪽 빈 자리), 템플릿에서 복사한 부위를 저장한다(06 ROOM-1a). 모두
 * pipeline의 트랜잭션 안이라 행동 기록까지 하나라도 실패하면 함께 되돌려진다.
 *
 * <p>측과 회원은 {@code command.actor()}에서 읽는다. 호실을 만드는 요청은 {@code RoomGate}가 없어서 부르는 쪽이 {@code
 * Actor.member(id).withSide(side)}로 넘긴다(09 3-1).
 */
public class NewRoomStateStore implements StateStore<RoomState> {

  private final RoomRecordRepository rooms;
  private final PartyRepository parties;
  private final RoomPartRepository parts;
  private final PartTemplateRepository templates;
  private final ActionRequestContext requestContext;
  private final String regionCode;
  private final String alias;
  private final LocalDate moveInDate;

  public NewRoomStateStore(
      RoomRecordRepository rooms,
      PartyRepository parties,
      RoomPartRepository parts,
      PartTemplateRepository templates,
      ActionRequestContext requestContext,
      String regionCode,
      String alias,
      LocalDate moveInDate) {
    this.rooms = rooms;
    this.parties = parties;
    this.parts = parts;
    this.templates = templates;
    this.requestContext = requestContext;
    this.regionCode = Objects.requireNonNull(regionCode);
    this.alias = Objects.requireNonNull(alias);
    this.moveInDate = moveInDate;
  }

  /** 만들기 전이면 빈 상태, 같은 키로 다시 온 요청이면 처음 만든 호실의 지금 상태. */
  @Override
  public RoomState load(ActionCommand command) {
    if (command.roomRecordId() == null) {
      return new RoomState(null);
    }
    RoomRecord room =
        rooms
            .findById(command.roomRecordId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return new RoomState(room.getStatus());
  }

  @Override
  public SavedState save(ActionCommand command, RoomState next) {
    if (next.status() != RoomStatus.LIVING || command.roomRecordId() != null) {
      throw new IllegalStateException("호실 기록 만들기만 저장해요");
    }
    UUID memberId = Objects.requireNonNull(command.actor().memberId(), "호실은 회원만 만들어요");
    PartySide side = Objects.requireNonNull(command.actor().side(), "만든 사람의 측이 없어요");
    // 행동 기록의 created_at과 같은 시각(09 3-1 ActionRequestContext)
    Instant at = requestContext.at();

    RoomRecord room = rooms.save(new RoomRecord(regionCode, alias, moveInDate, side, at));
    UUID roomId = room.getId();
    parties.save(Party.creator(roomId, side, memberId, at));
    parties.save(Party.empty(roomId, opposite(side), at));
    parts.saveAll(
        templates.findAllByOrderBySortOrder().stream()
            .map(template -> RoomPart.copyOf(roomId, template, at))
            .toList());
    return SavedState.created(ActionTarget.roomRecord(roomId));
  }

  private static PartySide opposite(PartySide side) {
    return side == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
  }
}
