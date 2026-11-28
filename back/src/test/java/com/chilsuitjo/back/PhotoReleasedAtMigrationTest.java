package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V13 사진이 상대에게 보이기 시작한 시각({@code photo_visibility.released_at}, #295)의 기존 줄 채우기. 앱과 따로 띄운
 * PostgreSQL에서 V11까지 올린 뒤 그때의 사진 줄을 넣고 최신으로 올려, 기준 측이 작성 중 점검에 붙인 사진만 가려지는지 본다. 기존 개발·운영 DB는 건드리지
 * 않는다.
 */
class PhotoReleasedAtMigrationTest {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Instant ATTACHED = Instant.parse("2026-11-20T01:00:00Z");
  private static final Instant REQUESTED = Instant.parse("2026-11-21T01:00:00Z");
  private static final Instant AFTER_REQUEST = Instant.parse("2026-11-22T01:00:00Z");

  private static JdbcTemplate jdbc;
  private static TransactionTemplate transaction;

  // 작성 중 점검(기준 측 = 세입자)
  private static UUID draftAuthorPhoto;
  private static UUID draftAuthorPartPhoto;
  private static UUID draftCounterpartPhoto;
  // 확인 요청을 보낸 점검(기준 측 = 세입자)
  private static UUID requestedAuthorPhoto;
  private static UUID requestedAuthorLaterPhoto;
  private static UUID requestedCounterpartPhoto;
  private static UUID requestedRoom;
  private static UUID requestedItem;
  // 확인 요청이 두 번 기록된 점검(지금 전이로는 생기지 않음, V13 백필이 첫 요청을 고르는지)
  private static UUID twiceRequestedAuthorPhoto;

  @BeforeAll
  static void migrateToPreviousThenAddLegacyRowsThenMigrate() {
    POSTGRES.start();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    flyway(dataSource, "11").migrate();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999993', '테스트', '이전동')"
            + " ON CONFLICT DO NOTHING");

    UUID draftRoom = room();
    UUID draftPart = part(draftRoom);
    UUID draft = inspection(draftRoom, "DRAFT");
    draftAuthorPhoto =
        photo(
            draftRoom,
            "inspection_item_id",
            item(draftRoom, draft, draftPart, "TENANT"),
            null,
            "TENANT",
            ATTACHED);
    draftAuthorPartPhoto = photo(draftRoom, "inspection_id", draft, draftPart, "TENANT", ATTACHED);
    draftCounterpartPhoto =
        photo(
            draftRoom,
            "inspection_item_id",
            item(draftRoom, draft, draftPart, "LANDLORD"),
            null,
            "LANDLORD",
            ATTACHED);

    requestedRoom = room();
    UUID requestedPart = part(requestedRoom);
    UUID requested = inspection(requestedRoom, "REQUESTED");
    requestedItem = item(requestedRoom, requested, requestedPart, "TENANT");
    requestedAuthorPhoto =
        photo(requestedRoom, "inspection_item_id", requestedItem, null, "TENANT", ATTACHED);
    requestedAuthorLaterPhoto =
        photo(requestedRoom, "inspection_item_id", requestedItem, null, "TENANT", AFTER_REQUEST);
    requestedCounterpartPhoto =
        photo(
            requestedRoom,
            "inspection_item_id",
            item(requestedRoom, requested, requestedPart, "LANDLORD"),
            null,
            "LANDLORD",
            ATTACHED);
    requestAction(requestedRoom, requested, 1, REQUESTED);

    UUID twiceRoom = room();
    UUID twicePart = part(twiceRoom);
    UUID twice = inspection(twiceRoom, "REQUESTED");
    twiceRequestedAuthorPhoto =
        photo(
            twiceRoom,
            "inspection_item_id",
            item(twiceRoom, twice, twicePart, "TENANT"),
            null,
            "TENANT",
            ATTACHED);
    requestAction(twiceRoom, twice, 1, AFTER_REQUEST);
    requestAction(twiceRoom, twice, 2, REQUESTED);

    flyway(dataSource, "latest").migrate();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void 기준_측이_작성_중_점검에_붙인_사진은_비운다() {
    assertThat(releasedAt(draftAuthorPhoto)).isNull();
    assertThat(releasedAt(draftAuthorPartPhoto)).isNull();
  }

  @Test
  void 상대가_작성_중에_더한_항목의_사진은_붙인_때부터_보인다() {
    assertThat(releasedAt(draftCounterpartPhoto)).isEqualTo(ATTACHED);
    assertThat(releasedAt(requestedCounterpartPhoto)).isEqualTo(ATTACHED);
  }

  @Test
  void 확인_요청_전에_붙인_기준_측_사진은_확인_요청_시각부터_보인다() {
    assertThat(releasedAt(requestedAuthorPhoto)).isEqualTo(REQUESTED);
  }

  @Test
  void 확인_요청_뒤에_붙인_기준_측_사진은_붙인_때부터_보인다() {
    assertThat(releasedAt(requestedAuthorLaterPhoto)).isEqualTo(AFTER_REQUEST);
  }

  @Test
  void 확인_요청이_여럿이면_첫_요청_시각부터_보인다() {
    assertThat(releasedAt(twiceRequestedAuthorPhoto)).isEqualTo(REQUESTED);
  }

  @Test
  void 칸을_모르는_이전_버전_앱이_넣은_줄도_보인다() {
    // 블루/그린 전환 중 이전 버전 앱이 같은 DB에 넣는 줄(04 8장 "이전 버전 앱과도 맞게")
    UUID id =
        photo(requestedRoom, "inspection_item_id", requestedItem, null, "LANDLORD", AFTER_REQUEST);

    assertThat(releasedAt(id)).isNotNull();
  }

  // ---- 준비: V11 모양의 줄 ----

  private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(target)
        .cleanDisabled(true)
        .load();
  }

  private static UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999993', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  private static UUID part(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '욕실', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room);
    return id;
  }

  private static UUID inspection(UUID room, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.inspection (id, room_record_id, kind, author_side, status, created_at, version)
        VALUES (?, ?, 'MOVE_IN', 'TENANT', ?, ?, 0)
        """,
        id,
        room,
        status,
        Timestamp.from(ATTACHED));
    return id;
  }

  /** 항목과 첫 판. 서로 가리키는 FK가 지연 검사라 한 트랜잭션에 넣는다. */
  private static UUID item(UUID room, UUID inspection, UUID part, String addedBy) {
    UUID id = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    transaction.executeWithoutResult(status -> {
      jdbc.update(
          """
          INSERT INTO state.inspection_item (id, room_record_id, inspection_id, current_version_id,
            status, added_by_side, created_at, version)
          VALUES (?, ?, ?, ?, 'PENDING', ?, ?, 0)
          """,
          id,
          room,
          inspection,
          version,
          addedBy,
          Timestamp.from(ATTACHED));
      jdbc.update(
          """
          INSERT INTO record.inspection_item_version (id, room_record_id, inspection_item_id, no,
            room_part_id, description, created_at)
          VALUES (?, ?, ?, 1, ?, '벽지 찢어짐', ?)
          """,
          version,
          room,
          id,
          part,
          Timestamp.from(ATTACHED));
    });
    return id;
  }

  /** 사진 카드와 공개 상태. 공개 상태는 V11 칸만 넣는다(이전 버전 앱과 같다). */
  private static UUID photo(
      UUID room, String column, UUID target, UUID part, String side, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO record.photo (id, room_record_id, "
            + column
            + ", room_part_id, stage, uploaded_by_side, original_key, thumb_key, original_sha256,"
            + " thumb_sha256, size_bytes, sort_order, created_at)"
            + " VALUES (?, ?, ?, ?, 'MOVE_IN', ?, ?, ?, ?, ?, 1,"
            + " (SELECT count(*) FROM record.photo WHERE room_record_id = ?), ?)",
        id,
        room,
        target,
        part,
        side,
        "photos/" + room + "/" + id + "/original.jpg",
        "photos/" + room + "/" + id + "/thumb.jpg",
        "0".repeat(64),
        "0".repeat(64),
        room,
        Timestamp.from(at));
    jdbc.update(
        "INSERT INTO state.photo_visibility (photo_id, room_record_id, created_at, version)"
            + " VALUES (?, ?, ?, 0)",
        id,
        room,
        Timestamp.from(at));
    return id;
  }

  private static void requestAction(UUID room, UUID inspection, int seq, Instant at) {
    jdbc.update(
        """
        INSERT INTO record.action (id, room_record_id, room_seq, prev_hash, hash, actor_side,
          actor_key, type, idempotency_key, request_id, hash_version, inspection_id, created_at)
        VALUES (?, ?, ?, ?, ?, 'TENANT', 'server', 'REQUEST_INSPECTION_CONFIRM', ?, ?, 1, ?, ?)
        """,
        UUID.randomUUID(),
        room,
        seq,
        bytes32(),
        bytes32(),
        UUID.randomUUID().toString(),
        UUID.randomUUID(),
        inspection,
        Timestamp.from(at));
  }

  private static byte[] bytes32() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  private static Instant releasedAt(UUID photo) {
    Timestamp at =
        jdbc.queryForObject(
            "SELECT released_at FROM state.photo_visibility WHERE photo_id = ?",
            Timestamp.class,
            photo);
    return at == null ? null : at.toInstant();
  }
}
