package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * pipeline 1단계의 호실 잠금과 읽기(09 3-1 {@code RoomGateReader}, ROOM-2). 호실 기록 행을 {@code FOR UPDATE}로 잠근 뒤
 * 읽은 값으로 {@link RoomGate}를 만든다. pipeline의 트랜잭션 안에서만 부른다.
 */
@Component
@RequiredArgsConstructor
public class JpaRoomGateReader implements RoomGateReader {

  private final RoomRecordRepository rooms;
  private final PartyRepository parties;

  /**
   * 회원이면 이 호실의 당사자인지 보고 아니면 {@code NOT_FOUND}다. 링크·서버·운영 주체는 호실 상태만 읽고, 측은 링크면 주체의 측, 서버·운영이면
   * null이다. 없는 호실은 누구에게나 {@code NOT_FOUND}다.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public RoomGate lockAndRead(UUID roomRecordId, Actor actor) {
    return gate(
        rooms
            .findByIdForUpdate(roomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)),
        actor);
  }

  /** 잠그지 않고 같은 값을 읽는다(상세 조회의 {@code availableActions}). 읽기 트랜잭션 안에서 부른다. */
  @Override
  @Transactional(readOnly = true)
  public RoomGate read(UUID roomRecordId, Actor actor) {
    return gate(
        rooms.findById(roomRecordId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)),
        actor);
  }

  private RoomGate gate(RoomRecord room, Actor actor) {
    if (!actor.isMember()) {
      // 공유 중단은 회원 기준이라 링크·서버·운영 주체면 거짓(09 3-1 RoomGate)
      return gateOf(room, actor.side(), false);
    }
    RoomSeats seats = new RoomSeats(room, parties.findByRoomRecordId(room.getId()));
    return memberGate(seats, seats.seatOf(actor.memberId()));
  }

  /**
   * 이미 읽은 호실과 자리로 회원의 {@link RoomGate}를 만든다. {@link #read}와 같은 값이다. 호실 목록({@link RoomViewService})이
   * 호실마다 다시 읽지 않고 같은 판단을 쓰게 둔다.
   */
  static RoomGate memberGate(RoomSeats seats, Party mine) {
    return gateOf(seats.room(), mine.getSide(), seats.sharingStoppedFor(mine));
  }

  private static RoomGate gateOf(RoomRecord room, PartySide side, boolean mySharingStopped) {
    return new RoomGate(
        room.getId(),
        side,
        room.getStatus() == RoomStatus.SETTLED,
        room.getJoinedIntoId() != null,
        mySharingStopped,
        room.getStatus() != RoomStatus.LIVING); // 거주 종료 또는 정산 완료
  }
}
