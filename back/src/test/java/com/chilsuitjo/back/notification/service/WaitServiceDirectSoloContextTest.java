package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱이 주입하는 {@link WaitService}의 바로 혼자 진행이 진짜 호실 자리({@code state.party})로 판단하는지(#113 Q5). 행을 직접 넣고
 * 테스트가 끝나면 되돌린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WaitServiceDirectSoloContextTest {

  private static final Instant STOPPED = Instant.parse("2026-11-20T03:00:00Z");

  @Autowired private WaitService waits;
  @Autowired private JdbcTemplate jdbc;

  private final RequestTarget repairCase =
      RequestTarget.of(TargetKind.REPAIR_CASE, UUID.randomUUID());

  @Test
  void 초대_전_호실은_상대_없음으로_바로_혼자_종료할_수_있다() {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", null, "EMPTY", null);

    assertThat(waits.canGoSoloDirectly(close(room)))
        .isEqualTo(SoloCheck.allowedBecause(SoloReason.NO_COUNTERPART));
  }

  @Test
  void 상대_자리가_공유를_그만뒀으면_그만둔_시각을_돌려준다() {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", member(), "SHARING_STOPPED", STOPPED);

    SoloCheck check = waits.canGoSoloDirectly(close(room));

    assertThat(check.reason()).isEqualTo(SoloReason.SHARING_STOPPED);
    assertThat(check.since()).isEqualTo(STOPPED);
  }

  @Test
  void 연결된_상대에게는_정해_둔_결과가_있어도_바로_열지_않는다() {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", member(), "CONNECTED", null);
    if (waits instanceof FakeWaitService fake) {
      fake.seedSolo(
          repairCase, RequestKind.CLOSE_CONFIRM, SoloCheck.allowedBecause(SoloReason.UNCONFIRMED));
    }

    assertThat(waits.canGoSoloDirectly(close(room))).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 없는_호실은_불가가_아니라_예외다() {
    assertThatThrownBy(() -> waits.canGoSoloDirectly(close(UUID.randomUUID())))
        .isInstanceOf(BusinessException.class);
  }

  private SoloQuery close(UUID room) {
    return new SoloQuery(room, RequestKind.CLOSE_CONFIRM, repairCase, PartySide.TENANT);
  }

  private UUID room() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, sms_enabled, status, created_at, version)"
            + " VALUES (?, true, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private void party(UUID room, String side, UUID memberId, String status, Instant stoppedAt) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, sharing_stopped_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side,
        memberId,
        status,
        stoppedAt == null ? null : Timestamp.from(stoppedAt));
  }
}
