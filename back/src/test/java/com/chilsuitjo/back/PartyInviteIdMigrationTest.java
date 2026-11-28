package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** V14: 자리에 초대 회차 {@code invite_id}를 더하고 기존 초대됨·거절됨 자리를 채운다(#437). 실제 PostgreSQL에서 확인한다. */
class PartyInviteIdMigrationTest extends SchemaTestSupport {

  @Test
  void 기존_초대됨_거절됨_자리만_서로_다른_회차로_채우고_나머지는_비워_둔다() {
    // 트랜잭션 안에서 V14 전 모양(칸 없음)으로 되돌린다. 테스트 뒤 DDL도 롤백된다.
    jdbc.execute("ALTER TABLE state.party DROP COLUMN invite_id");
    UUID room = room();
    UUID otherRoom = room();
    UUID invited = seat(room, "TENANT", "INVITED");
    UUID rejected = seat(otherRoom, "TENANT", "REJECTED");
    UUID numberOnly = seat(room, "LANDLORD", "NUMBER_ONLY");
    UUID empty = seat(otherRoom, "LANDLORD", "EMPTY");
    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              ScriptUtils.executeSqlScript(
                  connection, new ClassPathResource("db/migration/V14__add_party_invite_id.sql"));
              return null;
            });

    assertThat(inviteId(invited)).isNotNull();
    assertThat(inviteId(rejected)).isNotNull().isNotEqualTo(inviteId(invited));
    assertThat(inviteId(numberOnly)).isNull();
    assertThat(inviteId(empty)).isNull();
  }

  private Object inviteId(UUID party) {
    Map<String, Object> row =
        jdbc.queryForMap("SELECT invite_id FROM state.party WHERE id = ?", party);
    return row.get("invite_id");
  }

  private UUID seat(UUID room, String side, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, ?, CASE WHEN CAST(? AS varchar) = 'EMPTY' THEN NULL ELSE '01012345678' END, ?,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        status,
        status);
    return id;
  }
}
