package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.response.CursorPage;
import com.chilsuitjo.back.room.dto.RoomDetail;
import com.chilsuitjo.back.room.dto.RoomRegion;
import com.chilsuitjo.back.room.dto.RoomSummary;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.Region;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RegionRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.NextStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 호실 목록과 호실 상세(06 4-3 {@code GET /api/rooms}·{@code GET /api/rooms/{id}}, ROOM-1·2). 읽기만 한다. */
@Service
@RequiredArgsConstructor
public class RoomViewService {

  /** 목록 한 쪽의 기본 크기(06 4-0 "목록"). */
  public static final int DEFAULT_SIZE = 20;

  /** 목록 한 쪽의 최대 크기. */
  public static final int MAX_SIZE = 100;

  /**
   * 호실 상세 {@code availableActions}에 넣는 행동과 이름(API 끝 단어, 06 4-0). 지금 API가 있는 행동만 둔다. 초대({@code
   * invite}, #163)·거주 종료({@code move-out}) 등은 그 API를 만드는 PR에서 더한다(decisions 2026-11-24).
   */
  private static final Map<ActionType, String> ACTION_NAMES = actionNames();

  private final RoomRecordRepository rooms;
  private final PartyRepository parties;
  private final RegionRepository regions;
  private final RoomGateReader gates;

  /**
   * 내가 당사자인 호실 기록, 새것부터 {@code size}개. 합류 전 기록도 쓴 사람에게는 보인다(03 3-1). {@code cursor}는 앞 쪽 응답의 {@code
   * nextCursor}다. 형식이 틀리거나 크기가 범위 밖이면 {@code VALIDATION_FAILED}다.
   */
  @Transactional(readOnly = true)
  public CursorPage<RoomSummary> list(UUID memberId, String cursor, int size) {
    if (size < 1 || size > MAX_SIZE) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    Limit limit = Limit.of(size + 1);
    List<RoomRecord> page =
        cursor == null
            ? rooms.findMine(memberId, limit)
            : rooms.findMineBefore(memberId, parseCursor(cursor), limit);
    boolean more = page.size() > size;
    List<RoomRecord> shown = more ? page.subList(0, size) : page;
    if (shown.isEmpty()) {
      return new CursorPage<>(List.of(), null);
    }

    Map<UUID, List<Party>> seatsByRoom =
        parties.findByRoomRecordIdIn(shown.stream().map(RoomRecord::getId).toList()).stream()
            .collect(Collectors.groupingBy(Party::getRoomRecordId));
    Map<String, Region> regionByCode =
        regions
            .findAllById(shown.stream().map(RoomRecord::getRegionCode).distinct().toList())
            .stream()
            .collect(Collectors.toMap(Region::getCode, Function.identity()));

    List<RoomSummary> items =
        shown.stream()
            .map(room -> summary(room, seatsByRoom.get(room.getId()), regionByCode, memberId))
            .toList();
    return new CursorPage<>(items, more ? shown.getLast().getId().toString() : null);
  }

  /**
   * 호실 상세. 없는 호실과 남의 호실은 같은 {@code NOT_FOUND}다(ROOM-2a).
   *
   * <p>{@code availableActions}는 실제 요청과 같은 판단으로 만든다: 잠그지 않는 {@link RoomGateReader#read}로 읽은 값의
   * {@code RoomGate.rejection}(pipeline 3단계와 같음). 화면을 연 뒤 상태가 바뀌면 요청이 서버에서 다시 검사되어 거절된다.
   *
   * <p>공유를 그만둔 뒤에는 그 시각을 {@code asOf}로 준다(03 3-1, 불변식 18, {@code RoomSeats.visibleCutoffFor}). 요약 칸
   * 가운데 상태(거주 종료·정산 완료)는 그 뒤에도 양쪽에 보이는 예외다.
   */
  @Transactional(readOnly = true)
  public RoomDetail detail(UUID roomRecordId, UUID memberId) {
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    RoomRecord room =
        rooms.findById(roomRecordId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RoomSeats seats = new RoomSeats(room, parties.findByRoomRecordId(roomRecordId));
    Party mine = seats.seatOf(memberId);
    List<String> actions =
        ACTION_NAMES.entrySet().stream()
            .filter(action -> gate.rejection(action.getKey()).isEmpty())
            .map(Map.Entry::getValue)
            .toList();
    return new RoomDetail(
        room.getId(),
        room.getAlias(),
        region(room.getRegionCode()),
        room.getMoveInDate(),
        room.getStatus(),
        mine.getSide(),
        room.getCreatedBySide(),
        mine.getStatus(),
        seats.counterpartOf(mine).getStatus(),
        gate.joinedAway(),
        seats.visibleCutoffFor(mine),
        room.getCreatedAt(),
        actions);
  }

  private static RoomSummary summary(
      RoomRecord room, List<Party> seatsOfRoom, Map<String, Region> regionByCode, UUID memberId) {
    RoomSeats seats = new RoomSeats(room, seatsOfRoom);
    Party mine = seats.seatOf(memberId);
    RoomGate gate = JpaRoomGateReader.memberGate(seats, mine);
    return new RoomSummary(
        room.getId(),
        room.getAlias(),
        RoomRegion.of(regionOf(regionByCode, room.getRegionCode())),
        room.getStatus(),
        mine.getSide(),
        seats.counterpartOf(mine).getStatus(),
        gate.joinedAway(),
        NextStep.of(gate),
        room.getCreatedAt());
  }

  private RoomRegion region(String code) {
    return RoomRegion.of(
        regions
            .findById(code)
            .orElseThrow(() -> new IllegalStateException("코드표에 없는 법정동이에요: " + code)));
  }

  private static Region regionOf(Map<String, Region> regionByCode, String code) {
    Region region = regionByCode.get(code);
    if (region == null) {
      // room_record.region_code는 region을 가리키는 FK라 생길 수 없다
      throw new IllegalStateException("코드표에 없는 법정동이에요: " + code);
    }
    return region;
  }

  private static UUID parseCursor(String cursor) {
    try {
      return UUID.fromString(cursor);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
  }

  private static Map<ActionType, String> actionNames() {
    Map<ActionType, String> names = new LinkedHashMap<>();
    names.put(ActionType.REGISTER, "repair-cases"); // 하자 등록(POST /api/rooms/{id}/repair-cases)
    return names;
  }
}
