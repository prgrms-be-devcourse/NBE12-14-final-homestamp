package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 호실 읽기 구현 테스트의 준비물. 행을 직접 넣고, 테스트가 끝나면 되돌린다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
abstract class RoomReadersTestSupport {

  @Autowired JdbcTemplate jdbc;

  UUID room(String status) {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, ended_at, settled_at, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', ?, 'TENANT', ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        status,
        status.equals("LIVING") ? null : java.sql.Timestamp.from(Instant.now()),
        status.equals("SETTLED") ? java.sql.Timestamp.from(Instant.now()) : null);
    return id;
  }

  UUID member(boolean smsEnabled) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, sms_enabled, status, created_at, version)"
            + " VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id,
        smsEnabled);
    return id;
  }

  UUID party(UUID room, String side, UUID memberId, String status, Instant sharingStoppedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, sharing_stopped_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        memberId,
        status,
        sharingStoppedAt == null ? null : java.sql.Timestamp.from(sharingStoppedAt));
    return id;
  }

  UUID part(UUID room, String name, boolean hidden) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, zone, name, sort_order, hidden_at, created_at, version)
        VALUES (?, ?, '안방', ?, 0, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        name,
        hidden ? java.sql.Timestamp.from(Instant.now()) : null);
    return id;
  }
}
