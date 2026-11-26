package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 부위 조회(09 3장 "room ← 모두", ROOM-2a·3a). 진짜 {@code state.room_part}를 읽는다. */
class JpaRoomQueryServiceTest extends RoomReadersTestSupport {

  @Autowired private RoomQueryService service;

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

  private static void assertNotFound(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }
}
