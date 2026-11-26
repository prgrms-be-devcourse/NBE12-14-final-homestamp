package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V11 전달 확인 기한({@code notice.delivery_check_due_at})의 적용과 제약. 앱과 따로 띄운 PostgreSQL에서 바로 앞 버전(V10)까지
 * 올린 뒤 그때의 통지 줄을 넣고 V11을 올려, 기존 줄을 지우거나 기한을 지어내지 않는지 본다. 기존 개발·운영 DB는 건드리지 않는다.
 */
class NoticeDeliveryCheckDueMigrationTest {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
  private static final Instant SENT_AT = Instant.parse("2026-11-23T01:00:10Z");

  private static JdbcTemplate jdbc;
  private static UUID room;
  private static UUID party;
  private static Map<UUID, Map<String, Object>> beforeMigration;

  @BeforeAll
  static void migrateToPreviousThenAddLegacyRowsThenMigrate() {
    POSTGRES.start();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);

    flyway(dataSource, "10").migrate();
    room = UUID.randomUUID();
    party = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999994', '테스트', '이전동')"
            + " ON CONFLICT DO NOTHING");
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999994', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    jdbc.update(
        """
        INSERT INTO state.party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        party,
        room);
    // 바로 앞 버전까지의 앱(기한 없는 markSent)이 남길 수 있는 줄: 보내기 전, 보냄(첫 조회 예약), 전달됨, 보낸 뒤 실패
    List<UUID> legacy =
        List.of(
            legacyNotice("REQUESTED", null, null),
            legacyNotice("SENT", SENT_AT, SENT_AT.plusSeconds(60)),
            legacyNotice("DELIVERED", SENT_AT, null),
            legacyNotice("FAILED", SENT_AT, null));
    beforeMigration = new HashMap<>();
    for (UUID id : legacy) {
      beforeMigration.put(id, row(id));
    }

    flyway(dataSource, "latest").migrate();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void 기존_통지는_지워지거나_바뀌지_않고_기한을_지어내지_않는다() {
    // 다른 테스트가 같은 호실에 줄을 더하므로 마이그레이션 전에 넣은 줄만 센다
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.notice WHERE id = ANY (?)",
                Integer.class,
                (Object) beforeMigration.keySet().toArray(UUID[]::new)))
        .isEqualTo(beforeMigration.size());
    for (Map.Entry<UUID, Map<String, Object>> entry : beforeMigration.entrySet()) {
      Map<String, Object> after = row(entry.getKey());
      assertThat(after).containsAllEntriesOf(entry.getValue());
      assertThat(after)
          .containsEntry("delivery_check_due_at", null)
          .containsEntry("delivery_poll_token", null)
          .containsEntry("delivery_poll_started_at", null)
          .containsEntry("delivery_poll_protected_until", null);
    }
  }

  @Test
  void 기한이_없는_보내기_전_통지와_이전_버전_앱의_보낸_통지를_받는다() {
    // 이전 버전 앱이 블루/그린 전환 중 같은 DB에 적을 수 있는 줄(04 8장 "이전 버전 앱과도 맞게")
    UUID requested = legacyNotice("REQUESTED", null, null);
    UUID oldAppSent = legacyNotice("SENT", SENT_AT, SENT_AT.plusSeconds(60));

    assertThat(row(requested)).containsEntry("delivery_check_due_at", null);
    assertThat(row(oldAppSent)).containsEntry("delivery_check_due_at", null);
  }

  @Test
  void 보낸_시각보다_뒤인_기한을_받는다() {
    UUID id = legacyNotice("SENT", SENT_AT, SENT_AT.plusSeconds(60));

    jdbc.update(
        "UPDATE state.notice SET delivery_check_due_at = ? WHERE id = ?",
        Timestamp.from(SENT_AT.plus(Duration.ofDays(3))),
        id);

    assertThat(((Timestamp) row(id).get("delivery_check_due_at")).toInstant())
        .isEqualTo(SENT_AT.plus(Duration.ofDays(3)));
  }

  @Test
  void 보낸_시각과_같거나_앞선_기한과_보낸_시각_없는_기한을_거절한다() {
    UUID sent = legacyNotice("SENT", SENT_AT, SENT_AT.plusSeconds(60));
    UUID requested = legacyNotice("REQUESTED", null, null);

    for (Instant bad : List.of(SENT_AT, SENT_AT.minusSeconds(1))) {
      assertThatThrownBy(() ->
              jdbc.update(
                  "UPDATE state.notice SET delivery_check_due_at = ? WHERE id = ?",
                  Timestamp.from(bad),
                  sent))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_notice_delivery_check_due");
    }
    assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE state.notice SET delivery_check_due_at = ? WHERE id = ?",
                Timestamp.from(SENT_AT.plus(Duration.ofDays(3))),
                requested))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_notice_delivery_check_due");
    // 기한이 있는 줄의 보낸 시각을 지울 수도 없다
    jdbc.update(
        "UPDATE state.notice SET delivery_check_due_at = ? WHERE id = ?",
        Timestamp.from(SENT_AT.plus(Duration.ofDays(3))),
        sent);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE state.notice SET sent_at = NULL WHERE id = ?", sent))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_notice_delivery_check_due");
  }

  private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(target)
        .cleanDisabled(true)
        .load();
  }

  private static UUID legacyNotice(String status, Instant sentAt, Instant nextPollAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.notice (id, room_record_id, recipient_party_id, kind, channel, status,
          sent_at, provider_message_id, batch_lead_id, next_poll_at, created_at, version)
        VALUES (?, ?, ?, 'REQUEST', 'SMS', ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        room,
        party,
        status,
        sentAt == null ? null : Timestamp.from(sentAt),
        sentAt == null ? null : "fake-" + id,
        sentAt == null ? null : id,
        nextPollAt == null ? null : Timestamp.from(nextPollAt),
        Timestamp.from(SENT_AT.minusSeconds(10)));
    return id;
  }

  private static Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT * FROM state.notice WHERE id = ?", id);
  }
}
