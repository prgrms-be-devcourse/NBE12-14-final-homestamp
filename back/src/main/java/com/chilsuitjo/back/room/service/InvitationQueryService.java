package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.member.service.MemberQueryService;
import com.chilsuitjo.back.room.dto.InvitationSummary;
import com.chilsuitjo.back.room.dto.RoomRegion;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.Region;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RegionRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 받은 초대 목록(06 4-3 {@code GET /api/invitations}, ROOM-5, S-08). 내 인증 번호와 같은 번호로 초대된 자리를 보여 준다. 읽기만
 * 한다.
 *
 * <p>빼는 것:
 *
 * <ul>
 *   <li>인증 번호가 없으면(인증이 풀림·탈퇴) 빈 목록이다. 옛 번호로 받은 초대는 보이지 않는다(06 ACC-4c)
 *   <li>정산 완료된 호실. 거주 종료 전에 초대받은 상대는 정산 완료 전까지 연결할 수 있어서 거주 종료 호실은 보여 준다(연결 규칙 {@code
 *       PartyTransitions}와 같은 기준). 팀 최종 합의 전 임시 정책이다(#163 3-1 "3. 이미 초대된 상대의 연결"). 정해지면 여기와 연결 규칙을
 *       함께 바꾼다
 *   <li>합류 전 기록(새 행동을 받지 않음, 03 B)과 내가 이미 당사자인 호실({@code uq_party_room_member}, 불변식 13)
 * </ul>
 *
 * <p>같은 집 후보(ROOM-6)와 그만 받았던 번호의 옛 초대(NOTI-12)는 Should·Could라 넣지 않는다. 새로 만든 호실부터 준다(호실 ID가 UUID
 * v7).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationQueryService {

  private final MemberQueryService members;
  private final PartyRepository parties;
  private final RoomRecordRepository rooms;
  private final RegionRepository regions;

  public List<InvitationSummary> list(UUID memberId) {
    Optional<String> phone = members.verifiedPhone(memberId);
    if (phone.isEmpty()) {
      return List.of();
    }
    List<Party> invited = parties.findByStatusAndInvitePhone(PartyStatus.INVITED, phone.get());
    if (invited.isEmpty()) {
      return List.of();
    }
    Set<UUID> roomIds = invited.stream().map(Party::getRoomRecordId).collect(Collectors.toSet());
    Map<UUID, RoomRecord> roomById =
        rooms.findAllById(roomIds).stream()
            .collect(Collectors.toMap(RoomRecord::getId, Function.identity()));
    Map<UUID, List<Party>> seatsByRoom =
        parties.findByRoomRecordIdIn(roomIds).stream()
            .collect(Collectors.groupingBy(Party::getRoomRecordId));

    List<Candidate> candidates =
        invited.stream()
            .map(seat -> candidate(seat, roomById, seatsByRoom, memberId))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing((Candidate c) -> c.room().getId()).reversed())
            .toList();
    if (candidates.isEmpty()) {
      return List.of();
    }

    Map<String, Region> regionByCode =
        regions
            .findAllById(candidates.stream().map(c -> c.room().getRegionCode()).distinct().toList())
            .stream()
            .collect(Collectors.toMap(Region::getCode, Function.identity()));
    Map<UUID, Optional<String>> names =
        members.maskedNames(
            candidates.stream().map(Candidate::inviterId).filter(Objects::nonNull).toList());

    return candidates.stream()
        .map(c ->
            new InvitationSummary(
                c.seat().getId(),
                c.seat().getSide(),
                RoomRegion.of(regionByCode.get(c.room().getRegionCode())),
                c.inviterId() == null
                    ? null
                    : names.getOrDefault(c.inviterId(), Optional.empty()).orElse(null)))
        .toList();
  }

  /** 보여 줄 초대면 호실과 초대한 회원, 아니면 null. */
  private static Candidate candidate(
      Party seat,
      Map<UUID, RoomRecord> roomById,
      Map<UUID, List<Party>> seatsByRoom,
      UUID memberId) {
    RoomRecord room = roomById.get(seat.getRoomRecordId());
    if (room == null || room.getStatus() == RoomStatus.SETTLED || room.getJoinedIntoId() != null) {
      return null;
    }
    List<Party> seats = seatsByRoom.getOrDefault(room.getId(), List.of());
    if (seats.stream().anyMatch(s -> memberId.equals(s.getMemberId()))) {
      return null;
    }
    // 초대한 사람이 탈퇴했으면 상대가 연결된 적 없는 호실이라 바로 숨기고 지운다(03 6-4 "혼자만 쓴 기록", 06 KEEP-4a)
    if (seats.stream().anyMatch(s -> s.getStatus() == PartyStatus.WITHDRAWN)) {
      return null;
    }
    UUID inviterId =
        seats.stream()
            .filter(s -> s.getSide() == room.getCreatedBySide())
            .map(Party::getMemberId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    return new Candidate(seat, room, inviterId);
  }

  private record Candidate(Party seat, RoomRecord room, UUID inviterId) {}
}
