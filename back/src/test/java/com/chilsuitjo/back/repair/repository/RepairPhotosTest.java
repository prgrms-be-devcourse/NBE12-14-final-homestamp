package com.chilsuitjo.back.repair.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.payment.service.PaymentFixtures;
import com.chilsuitjo.back.payment.service.RepairPhotoScope;
import com.chilsuitjo.back.photo.dto.PhotoQuota;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수리 건 카드 사진 한도(06 0-1 {@code photo.max.repair}, decisions 2026-11-28)에 지불 증빙이 함께 세어지는지 본다. 수리 쪽
 * 사진(하자 보충·진행 상황 등)과 지불 증빙이 모두 이 목록({@code RepairPhotos.quota}, 포트 {@code RepairPhotoScope})으로 한도를
 * 잰다. 한도를 넘었을 때 {@code PHOTO_LIMIT}을 내는 것은 사진 창구 몫이라 {@code JpaPhotoServiceAttachTest}가 본다(테스트의 가짜
 * 사진 창구는 한도를 보지 않음). 테스트는 끝나면 되돌린다.
 */
@Import({TestcontainersConfiguration.class, PaymentFixtures.class})
@SpringBootTest(
    properties =
        "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public")
@Transactional
@ActiveProfiles("test")
class RepairPhotosTest {

  @Autowired private RepairPhotoScope scope;
  @Autowired private PaymentFixtures payments;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void region() {
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동') ON CONFLICT DO NOTHING");
  }

  @Test
  void 수리_건_카드_한도에_그_건의_지불이_함께_들어가고_다른_수리_건의_지불은_빠진다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID other = repair(room);
    UUID first = payments.insertConfirmed(room, repair, 82_000);
    UUID second = payments.insertConfirmed(room, repair, 30_000);
    UUID elsewhere = payments.insertConfirmed(room, other, 10_000);

    PhotoQuota quota = scope.quota(room, repair);

    assertThat(quota.max()).isEqualTo(30);
    assertThat(quota.countedTargets())
        .contains(
            new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, repair),
            new PhotoTarget.Ref(PhotoTargetType.PAYMENT, first),
            new PhotoTarget.Ref(PhotoTargetType.PAYMENT, second))
        .doesNotContain(new PhotoTarget.Ref(PhotoTargetType.PAYMENT, elsewhere));
  }

  @Test
  void 다른_호실_ID로_물으면_지불을_세지_않는다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID payment = payments.insertConfirmed(room, repair, 82_000);

    assertThat(scope.quota(room(), repair).countedTargets())
        .doesNotContain(new PhotoTarget.Ref(PhotoTargetType.PAYMENT, payment));
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  private UUID repair(UUID room) {
    UUID part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '안방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        room);
    UUID id = UUID.randomUUID();
    UUID content = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO repair_case (id, room_record_id, current_content_id, status, defect_ack,
          money_status, created_by_side, created_at, version)
        VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        content);
    jdbc.update(
        """
        INSERT INTO repair_content (id, room_record_id, repair_case_id, no, room_part_id, title,
          created_by_side, created_at)
        VALUES (?, ?, ?, 1, ?, '누수', 'TENANT', CURRENT_TIMESTAMP)
        """,
        content,
        room,
        id,
        part);
    return id;
  }
}
