package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class PaymentResponseMigrationTest extends SchemaTestSupport {

  @Test
  void 기존_NONE만_PENDING으로_바꾸고_새_지불의_기본값도_바꾼다() {
    // 트랜잭션 안에서 V5까지의 응답 제약·기본값을 복원한다. 테스트 뒤 DDL도 롤백된다.
    jdbc.execute("ALTER TABLE state.payment DROP CONSTRAINT payment_response_check");
    jdbc.execute(
        """
        ALTER TABLE state.payment ADD CONSTRAINT payment_response_check
        CHECK (response IN ('NONE', 'ACK', 'NEED_MORE', 'DISPUTE'))
        """);
    jdbc.execute("ALTER TABLE state.payment ALTER COLUMN response SET DEFAULT 'NONE'");
    UUID room = room();
    UUID repair = repair(room);
    UUID pending = payment(room, repair);
    UUID ack = payment(room, repair);
    UUID needMore = payment(room, repair);
    UUID dispute = payment(room, repair);
    jdbc.update("UPDATE state.payment SET response = 'ACK' WHERE id = ?", ack);
    jdbc.update("UPDATE state.payment SET response = 'NEED_MORE' WHERE id = ?", needMore);
    jdbc.update("UPDATE state.payment SET response = 'DISPUTE' WHERE id = ?", dispute);
    assertThat(response(pending)).isEqualTo("NONE");
    // 실제 마이그레이션 전에는 기존 행의 FK 검사가 커밋 때 끝나 있다.
    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              ScriptUtils.executeSqlScript(
                  connection,
                  new ClassPathResource(
                      "db/migration/V6__rename_payment_response_none_to_pending.sql"));
              return null;
            });

    assertThat(response(pending)).isEqualTo("PENDING");
    assertThat(response(ack)).isEqualTo("ACK");
    assertThat(response(needMore)).isEqualTo("NEED_MORE");
    assertThat(response(dispute)).isEqualTo("DISPUTE");
    jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
    assertThat(response(payment(room, repair))).isEqualTo("PENDING");
    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"NONE", "INVALID"})
  void 바뀐_제약은_예전_응답과_알_수_없는_응답을_거절한다(String response) {
    UUID room = room();
    UUID payment = payment(room, repair(room));

    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "UPDATE state.payment SET response = ? WHERE id = ?", response, payment));
    assertThat(error.getMessage()).contains("payment_response_check");
  }

  private String response(UUID payment) {
    return jdbc.queryForObject(
        "SELECT response FROM state.payment WHERE id = ?", String.class, payment);
  }

  private UUID payment(UUID room, UUID repair) {
    UUID payment = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.payment (id, room_record_id, repair_case_id, current_version_id,
          confirm_strength, created_at, version)
        VALUES (?, ?, ?, ?, 'ONE_SIDE', CURRENT_TIMESTAMP, 0)
        """,
        payment,
        room,
        repair,
        version);
    jdbc.update(
        """
        INSERT INTO record.payment_version (id, room_record_id, payment_id, no, payer_kind,
          payee_kind, amount, paid_on, recorded_by_side, created_at)
        VALUES (?, ?, ?, 1, 'TENANT', 'VENDOR', 10000, CURRENT_DATE, 'TENANT', CURRENT_TIMESTAMP)
        """,
        version,
        room,
        payment);
    return payment;
  }
}
