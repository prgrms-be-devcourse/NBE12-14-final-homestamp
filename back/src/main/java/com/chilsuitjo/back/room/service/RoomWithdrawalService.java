package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.SmsCancellation;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.PartyStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴에서 호실 쪽 일(03 B "탈퇴", 06 KEEP-4). 그 회원이 앉아 있는 자리(연결됨·공유 중단)를 탈퇴함으로 바꾼다. 회원 가림·토큰 폐기와 같은 트랜잭션에서
 * auth {@code WithdrawService}가 부른다.
 *
 * <p><b>행동 기록을 남기지 않는다.</b> 탈퇴는 회원 변경(신원 칸 예외)이라 행동이 아니다(05 2-3 "계정(호실 밖)"). 그래서 pipeline의 행동 시각
 * 창구({@code ActionRequestContext})가 열려 있지 않다. 그래서 열린 요청 닫기·발급 링크 무효·보내지 않은 문자 취소는 D가 둔 시각을 받는
 * 입구(#435)를 탈퇴 시각으로 부른다({@link #leave}).
 *
 * <p><b>잠금 순서</b>(#424 D 제안): 호실(ID 순) → 회원 행 → 리프레시 토큰. 연결처럼 호실을 먼저 잠그는 흐름과 같은 방향이다. 그래서 두 단계로
 * 나눈다.
 *
 * <ol>
 *   <li>{@link #lockRoomsOf}: 회원을 잠그기 전에 자리 목록을 읽고 그 호실을 ID 순으로 잠근다
 *   <li>(부르는 쪽이 회원 행을 잠근다)
 *   <li>{@link #leave}: 회원을 잠근 뒤 자리 목록을 다시 읽는다. 첫 단계 뒤 같은 회원의 연결이 먼저 커밋돼 잠그지 않은 호실이 보이면, 지금 잠그면 순서가
 *       깨지므로 아무것도 바꾸지 않고 거짓을 돌려준다. 부르는 쪽은 트랜잭션을 되돌리고 처음부터 다시 한다. 회원 행을 잡은 뒤에 시작하는 연결은 회원 행 공유 잠금에서
 *       기다렸다가 탈퇴한 회원으로 거절되므로({@code MemberQueryService.holdActive}) 다시 읽은 목록은 더 늘지 않는다
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class RoomWithdrawalService {

  private static final EnumSet<PartyStatus> OCCUPIED =
      EnumSet.of(PartyStatus.CONNECTED, PartyStatus.SHARING_STOPPED);

  private final PartyRepository parties;
  private final RoomRecordRepository rooms;
  private final WaitService waits;
  private final InviteLinks inviteLinks;
  private final SmsCancellation smsCancellation;

  /**
   * 이 회원이 앉아 있는 호실을 ID 순으로 잠그고 그 ID를 돌려준다. 회원 행을 잠그기 전에 부른다.
   *
   * @return 잠근 호실 ID(오름차순)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> lockRoomsOf(UUID memberId) {
    List<UUID> roomIds = occupiedRooms(memberId);
    for (UUID roomId : roomIds) {
      rooms.findByIdForUpdate(roomId).orElseThrow();
    }
    return roomIds;
  }

  /**
   * 잠근 호실의 이 회원 자리를 모두 탈퇴함으로 바꾸고, 그 호실마다 같은 탈퇴 시각으로 차례로 한다(#424 합의, #435 계약). 공유를 그만둔 시각은 지우지
   * 않는다({@link Party#withdraw}). 회원 행을 잠근 뒤 부른다.
   *
   * <ol>
   *   <li>열린 확인 요청을 양방향·모든 종류 닫는다({@code COUNTERPART_WITHDRAWN})
   *   <li>이 회원이 그 호실에서 발급한 초대·정산 초대 링크를 무효로 한다({@code ISSUER_WITHDRAWN})
   *   <li>2가 돌려준 링크가 담긴 보내지 않은({@code PENDING}) 문자를 취소한다
   * </ol>
   *
   * <p>셋 다 실패를 숨기지 않으므로 하나라도 던지면 탈퇴 전체가 되돌아간다.
   *
   * <p>상대가 연결된 적 없는 호실(06 KEEP-4a)도 같은 방법으로 자리만 바꾼다. 그 호실은 받은 초대·연결에서 빠지고({@code
   * InvitationQueryService}, {@code JpaRoomGateReader#lockAndReadAsInvitee}) 통째 삭제는 삭제 작업이 한다(아직
   * 없음).
   *
   * @param locked {@link #lockRoomsOf}가 잠근 호실
   * @param at 탈퇴 시각(회원 {@code withdrawn_at}과 같은 값)
   * @return 바꿨으면 참. 잠그지 않은 호실에 이 회원 자리가 새로 보이면 아무것도 바꾸지 않고 거짓(부르는 쪽이 되돌리고 다시 함)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean leave(UUID memberId, Collection<UUID> locked, Instant at) {
    Objects.requireNonNull(memberId, "memberId");
    Objects.requireNonNull(at, "at");
    List<UUID> now = occupiedRooms(memberId);
    if (!locked.containsAll(now)) {
      return false;
    }
    for (UUID roomId : now) {
      parties.findByRoomRecordId(roomId).stream()
          .filter(party -> memberId.equals(party.getMemberId()))
          .filter(Party::isOccupied)
          .forEach(Party::withdraw);
    }
    parties.flush();
    for (UUID roomId : now) {
      waits.closeAll(
          roomId, ClosedReason.COUNTERPART_WITHDRAWN, EnumSet.allOf(RequestKind.class), at);
      List<UUID> links =
          inviteLinks.invalidateIssuedBy(roomId, memberId, LinkInvalidReason.ISSUER_WITHDRAWN, at);
      smsCancellation.cancelPendingSmsOnWithdrawal(roomId, links);
    }
    return true;
  }

  /**
   * 이 회원이 앉아 있는 호실, ID 순. 순서는 PostgreSQL {@code uuid} 순서(바이트 순서)와 같은 16진 글자 순이다. {@link
   * UUID#compareTo}는 부호 있는 비교라 DB의 {@code ORDER BY room_record_id}와 다를 수 있어 쓰지 않는다.
   */
  private List<UUID> occupiedRooms(UUID memberId) {
    return parties.findByMemberIdAndStatusIn(memberId, OCCUPIED).stream()
        .map(Party::getRoomRecordId)
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .toList();
  }
}
