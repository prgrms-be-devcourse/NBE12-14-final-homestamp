package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.room.dto.RoomPartSummary;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 다른 도메인이 호실 쪽 값을 읽는 창구(09 3장 "room ← 모두"). 권한 검사는 {@link RoomAccessService}가 맡는다. */
public interface RoomQueryService {

  /**
   * 이 호실 기록의 부위를 돌려준다. 없는 부위와 다른 호실의 부위는 모두 {@code NOT_FOUND}다(ROOM-2a: 남의 호실 부위는 있는지도 알 수 없다). 그래서
   * 권한 검사를 통과한 호실 ID를 넘기면, 요청 본문에 남의 부위 ID가 와도 막힌다. 숨긴 부위도 지난 기록이 가리키므로 돌려준다.
   */
  RoomPartSummary findPart(UUID roomRecordId, UUID roomPartId);

  /**
   * {@link #findPart}를 여러 부위에 한 번에(목록·링크처럼 항목마다 부르지 않으려고, #397 리뷰). 하나라도 없거나 다른 호실의 부위면 {@code
   * NOT_FOUND}다. 같은 ID가 여러 번 와도 된다. 비었으면 빈 Map.
   *
   * @return 부위 ID → 부위
   */
  Map<UUID, RoomPartSummary> findParts(UUID roomRecordId, Collection<UUID> roomPartIds);

  /**
   * 이 회원에게 보이는 부위 상태(새 기록을 받을지 판단할 때). 공유를 그만둔 뒤(어느 쪽이든, 상대가 탈퇴하지 않았을 때)에는 부위 목록({@code GET
   * /api/rooms/{id}/parts}, #180)과 같게 그 시각까지의 부위 행동과 이 회원 측의 행동으로 다시 계산한 상태다(03 6-6 9번, 불변식 18,
   * #183). 그래서 그 뒤 상대가 더한 부위는 {@code NOT_FOUND}이고, 상대가 바꾼 숨김은 드러나지 않는다. 없는 부위·다른 호실의 부위·당사자가 아닌 회원도
   * {@code NOT_FOUND}다. 공유 중단이 없으면 {@link #findPart}와 같다.
   */
  RoomPartSummary findPartAsSeenBy(UUID roomRecordId, UUID roomPartId, UUID memberId);
}
