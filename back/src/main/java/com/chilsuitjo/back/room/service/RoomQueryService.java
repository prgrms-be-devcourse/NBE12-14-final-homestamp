package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.room.dto.MemberRoom;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import java.util.List;
import java.util.UUID;

/** 다른 도메인이 호실 쪽 값을 읽는 창구(09 3장 "room ← 모두"). 권한 검사는 {@link RoomAccessService}가 맡는다. */
public interface RoomQueryService {

  /**
   * 이 호실 기록의 부위를 돌려준다. 없는 부위와 다른 호실의 부위는 모두 {@code NOT_FOUND}다(ROOM-2a: 남의 호실 부위는 있는지도 알 수 없다). 그래서
   * 권한 검사를 통과한 호실 ID를 넘기면, 요청 본문에 남의 부위 ID가 와도 막힌다. 숨긴 부위도 지난 기록이 가리키므로 돌려준다.
   */
  RoomPartSummary findPart(UUID roomRecordId, UUID roomPartId);

  /**
   * 이 회원에게 보이는 부위 상태(새 기록을 받을지 판단할 때). 공유를 그만둔 뒤(어느 쪽이든, 상대가 탈퇴하지 않았을 때)에는 부위 목록({@code GET
   * /api/rooms/{id}/parts}, #180)과 같게 그 시각까지의 부위 행동과 이 회원 측의 행동으로 다시 계산한 상태다(03 6-6 9번, 불변식 18,
   * #183). 그래서 그 뒤 상대가 더한 부위는 {@code NOT_FOUND}이고, 상대가 바꾼 숨김은 드러나지 않는다. 없는 부위·다른 호실의 부위·당사자가 아닌 회원도
   * {@code NOT_FOUND}다. 공유 중단이 없으면 {@link #findPart}와 같다.
   */
  RoomPartSummary findPartAsSeenBy(UUID roomRecordId, UUID roomPartId, UUID memberId);

  /**
   * 이 회원이 당사자인 호실 기록 전부(홈이 호실마다 읽을 범위, #369·#370 A-8). 내 호실 목록({@code GET /api/rooms})과 같은 호실을 같은
   * 순서(새것부터)로 주지만 커서·상한이 없다(A-8: 첫 쪽에 없는 호실의 요청이 빠지지 않게). 호실을 거르지 않는다: 정산 완료·합류 전 기록·거주 중 거르기는 부르는
   * 쪽이 한다. 당사자인 호실이 없으면 빈 목록이다.
   */
  List<MemberRoom> roomsOf(UUID memberId);
}
