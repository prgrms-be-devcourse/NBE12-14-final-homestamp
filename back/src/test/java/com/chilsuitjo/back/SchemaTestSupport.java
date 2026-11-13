package com.chilsuitjo.back;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
abstract class SchemaTestSupport {
  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void region() {
    jdbc.update("INSERT INTO region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')");
  }

  protected UUID room() {
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

  protected UUID part(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '안방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room);
    return id;
  }

  protected UUID repair(UUID room) {
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
        part(room));
    return id;
  }

  protected UUID request(UUID room, String kind, UUID repair, String status, String side) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO response_request (id, room_record_id, kind, repair_case_id,
          requested_by_side, status, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        kind,
        repair,
        side,
        status);
    return id;
  }
}
