package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  void 상대_자리가_공유를_그만뒀으면_하자_확인은_그만둔_시각을_돌려주고_수리_종료는_열지_않는다() {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", member(), "SHARING_STOPPED", STOPPED);

    SoloCheck check =
        waits.canGoSoloDirectly(
            new SoloQuery(room, RequestKind.DEFECT_CONFIRM, repairCase, PartySide.TENANT));

    assertThat(check.reason()).isEqualTo(SoloReason.SHARING_STOPPED);
    assertThat(check.since()).isEqualTo(STOPPED);
    // 수리 종료는 공유 중단을 혼자 종료 이유로 쓰지 않는다(#113 ③')
    assertThat(waits.canGoSoloDirectly(close(room))).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 연결된_상대에게는_정해_둔_결과가_있어도_바로_열지_않는다() {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", member(), "CONNECTED", null);
    // 지금 운영에서도 쓰이는 빈은 가짜다. 진짜 구현으로 바뀌면 이 테스트가 깨져 다시 보게 한다(조용히 seed를 건너뛰지 않게)
    assertThat(waits).isInstanceOf(FakeWaitService.class);
    ((FakeWaitService) waits)
        .seedSolo(
            repairCase,
            RequestKind.CLOSE_CONFIRM,
            SoloCheck.allowedBecause(SoloReason.UNCONFIRMED));

    assertThat(waits.canGoSoloDirectly(close(room))).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 번호만_있는_자리도_상대_없음이다() {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", null, "NUMBER_ONLY", null);

    assertThat(waits.canGoSoloDirectly(close(room)))
        .isEqualTo(SoloCheck.allowedBecause(SoloReason.NO_COUNTERPART));
  }

  @ParameterizedTest
  @ValueSource(strings = {"INVITED", "REJECTED"})
  void 연결_전이나_거절한_자리는_열지_않는다(String status) {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", null, status, null);

    assertThat(waits.canGoSoloDirectly(close(room))).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 연결_전_자리면_정산_끝내기는_바로_혼자_진행하고_거절한_자리는_열지_않는다() {
    // 03 1-4 ③: 연결 전이면 정산은 혼자 정리하고 바로 끝낸다. 진짜 자리 읽기로 본다
    UUID invited = room();
    party(invited, "TENANT", member(), "CONNECTED", null);
    party(invited, "LANDLORD", null, "INVITED", null);
    UUID rejected = room();
    party(rejected, "TENANT", member(), "CONNECTED", null);
    party(rejected, "LANDLORD", null, "REJECTED", null);

    assertThat(waits.canGoSoloDirectly(settleFinish(invited)))
        .isEqualTo(SoloCheck.allowedBecause(SoloReason.NOT_CONNECTED));
    assertThat(waits.canGoSoloDirectly(settleFinish(rejected))).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 탈퇴한_자리는_남은_공유_중단_시각이_있어도_아직_열지_않는다() {
    UUID room = room();
    party(room, "TENANT", member(), "CONNECTED", null);
    party(room, "LANDLORD", member(), "WITHDRAWN", STOPPED);

    assertThat(waits.canGoSoloDirectly(close(room))).isEqualTo(SoloCheck.notYet(null));
  }

  @Test
  void 없는_호실은_불가가_아니라_예외다() {
    assertThatThrownBy(() -> waits.canGoSoloDirectly(close(UUID.randomUUID())))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  private SoloQuery settleFinish(UUID room) {
    return new SoloQuery(
        room,
        RequestKind.SETTLE_FINISH,
        RequestTarget.of(TargetKind.SETTLEMENT, UUID.randomUUID()),
        PartySide.TENANT);
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
