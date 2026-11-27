package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.MemberRoom;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.dto.RoomSummary;
import com.chilsuitjo.back.room.rule.RoomStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 부위 조회(ROOM-2a·3a)와 내 호실 범위(#393, #370 A-8). 09 3장 "room ← 모두". 진짜 DB를 읽는다. */
class JpaRoomQueryServiceTest extends RoomReadersTestSupport {

  @Autowired private RoomQueryService service;
  @Autowired private RoomViewService views;
  @PersistenceContext private EntityManager entityManager;

  @Test
  void 진짜_구현이_쓰인다() {
    assertThat(service).isInstanceOf(JpaRoomQueryService.class);
  }

  @Test
  void 이_호실의_부위는_이름과_숨김_여부를_돌려받는다() {
    UUID room = room("LIVING");
    UUID ceiling = part(room, "천장", false);
    UUID veranda = part(room, "베란다", true);

    assertThat(service.findPart(room, ceiling)).isEqualTo(new RoomPartSummary("천장", false, true));
    // 숨긴 부위도 지난 기록이 가리키므로 돌려준다
    assertThat(service.findPart(room, veranda)).isEqualTo(new RoomPartSummary("베란다", true, true));
  }

  @Test
  void 없는_부위와_다른_호실의_부위는_NOT_FOUND() {
    UUID room = room("LIVING");
    UUID other = part(room("LIVING"), "천장", false);

    assertNotFound(() -> service.findPart(room, UUID.randomUUID()));
    assertNotFound(() -> service.findPart(room, other));
  }

  @Test
  void 내_호실_전부를_목록과_같은_순서로_상한_없이_준다() {
    UUID me = member(true);
    for (int i = 0; i < RoomViewService.DEFAULT_SIZE + 1; i++) {
      UUID room = room("LIVING");
      party(room, "TENANT", me, "CONNECTED", null);
      party(room, "LANDLORD", null, "EMPTY", null);
    }
    UUID others = room("LIVING");
    party(others, "TENANT", member(true), "CONNECTED", null);
    party(others, "LANDLORD", null, "EMPTY", null);

    List<MemberRoom> mine = service.roomsOf(me);

    // 첫 쪽(20개)에 없는 21번째 호실도 담기고, 남의 호실은 담기지 않는다
    assertThat(mine).hasSize(RoomViewService.DEFAULT_SIZE + 1);
    assertThat(mine)
        .extracting(MemberRoom::roomRecordId)
        .containsExactlyElementsOf(
            views.list(me, null, RoomViewService.MAX_SIZE).items().stream()
                .map(RoomSummary::id)
                .toList());
  }

  @Test
  void 당사자인_호실이_없으면_빈_목록() {
    assertThat(service.roomsOf(member(true))).isEmpty();
  }

  @Test
  void 정산_완료와_합류_전_기록도_거르지_않고_측과_상태를_준다() {
    UUID me = member(true);
    UUID settled = room("SETTLED");
    party(settled, "LANDLORD", me, "CONNECTED", null);
    party(settled, "TENANT", member(true), "CONNECTED", null);
    UUID away = room("LIVING");
    party(away, "TENANT", me, "CONNECTED", null);
    party(away, "LANDLORD", null, "EMPTY", null);
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        settled,
        away);

    assertThat(byRoom(service.roomsOf(me)))
        .containsEntry(
            settled,
            new MemberRoom(settled, PartySide.LANDLORD, RoomStatus.SETTLED, false, false, null))
        .containsEntry(
            away, new MemberRoom(away, PartySide.TENANT, RoomStatus.LIVING, true, false, null));
  }

  @Test
  void 누가_그만뒀든_공유_중단이면_이른_시각을_기준으로_준다() {
    UUID me = member(true);
    Instant early = Instant.parse("2026-11-01T00:00:00Z");
    Instant late = Instant.parse("2026-11-05T00:00:00Z");
    UUID mineStopped = room("LIVING");
    party(mineStopped, "TENANT", me, "SHARING_STOPPED", early);
    party(mineStopped, "LANDLORD", member(true), "CONNECTED", null);
    UUID theirsStopped = room("LIVING");
    party(theirsStopped, "TENANT", me, "CONNECTED", null);
    party(theirsStopped, "LANDLORD", member(true), "SHARING_STOPPED", early);
    UUID bothStopped = room("LIVING");
    party(bothStopped, "TENANT", me, "SHARING_STOPPED", late);
    party(bothStopped, "LANDLORD", member(true), "SHARING_STOPPED", early);

    Map<UUID, MemberRoom> rooms = byRoom(service.roomsOf(me));

    for (UUID room : List.of(mineStopped, theirsStopped, bothStopped)) {
      assertThat(rooms.get(room).sharingStopped()).isTrue();
      assertThat(rooms.get(room).asOf()).isEqualTo(early);
    }
  }

  @Test
  void 상대가_탈퇴했으면_내가_그만둔_채여도_제한이_풀린다() {
    UUID me = member(true);
    UUID room = room("LIVING");
    party(room, "TENANT", me, "SHARING_STOPPED", Instant.parse("2026-11-01T00:00:00Z"));
    party(room, "LANDLORD", null, "WITHDRAWN", null);

    MemberRoom seen = service.roomsOf(me).getFirst();

    assertThat(seen.sharingStopped()).isFalse();
    assertThat(seen.asOf()).isNull();
  }

  @Test
  void 상대가_그만둔_뒤_탈퇴했으면_그_시각이_남아도_제한이_없다() {
    UUID me = member(true);
    UUID room = room("LIVING");
    party(room, "TENANT", me, "CONNECTED", null);
    party(room, "LANDLORD", null, "WITHDRAWN", Instant.parse("2026-11-01T00:00:00Z"));

    assertThat(service.roomsOf(me))
        .containsExactly(
            new MemberRoom(room, PartySide.TENANT, RoomStatus.LIVING, false, false, null));
  }

  @Test
  void 호실마다_내_자리를_따로_찾는다() {
    UUID me = member(true);
    UUID landlordRoom = room("LIVING");
    party(landlordRoom, "LANDLORD", me, "CONNECTED", null);
    party(landlordRoom, "TENANT", member(true), "CONNECTED", null);
    UUID tenantRoom = room("ENDED");
    party(tenantRoom, "TENANT", me, "CONNECTED", null);
    party(tenantRoom, "LANDLORD", member(true), "CONNECTED", null);

    Map<UUID, MemberRoom> rooms = byRoom(service.roomsOf(me));

    assertThat(rooms.get(landlordRoom).mySide()).isEqualTo(PartySide.LANDLORD);
    assertThat(rooms.get(landlordRoom).status()).isEqualTo(RoomStatus.LIVING);
    assertThat(rooms.get(tenantRoom).mySide()).isEqualTo(PartySide.TENANT);
    assertThat(rooms.get(tenantRoom).status()).isEqualTo(RoomStatus.ENDED);
  }

  @Test
  void 그만뒀다가_다시_공유하면_제한이_풀린다() {
    UUID me = member(true);
    Instant stoppedAt = Instant.parse("2026-11-01T00:00:00Z");
    UUID room = room("LIVING");
    UUID mine = party(room, "TENANT", me, "SHARING_STOPPED", stoppedAt);
    party(room, "LANDLORD", member(true), "CONNECTED", null);
    assertThat(service.roomsOf(me))
        .containsExactly(
            new MemberRoom(room, PartySide.TENANT, RoomStatus.LIVING, false, true, stoppedAt));

    // 다시 공유하면 연결됨으로 돌아가고 sharing_stopped_at을 비운다(05 party, 03 B). 다시 공유 API가 아직 없어 그 결과 행을 직접 만든다
    jdbc.update(
        "UPDATE state.party SET status = 'CONNECTED', sharing_stopped_at = NULL WHERE id = ?",
        mine);
    entityManager.clear(); // 위에서 읽어 둔 자리를 버리고 DB에서 다시 읽게

    assertThat(service.roomsOf(me))
        .containsExactly(
            new MemberRoom(room, PartySide.TENANT, RoomStatus.LIVING, false, false, null));
  }

  private static Map<UUID, MemberRoom> byRoom(List<MemberRoom> rooms) {
    return rooms.stream().collect(Collectors.toMap(MemberRoom::roomRecordId, Function.identity()));
  }

  private static void assertNotFound(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }
}
