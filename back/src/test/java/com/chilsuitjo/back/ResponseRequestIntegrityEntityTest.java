package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.RequestKind;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 응답 요청을 엔티티로 저장할 때 V1의 복합 FK와 CHECK가 잘못된 행을 막는지 본다(#117 4단계). 제약 자체는 SQL 테스트({@code
 * RoomBoundarySchemaTest}·{@code SituationCheckSchemaTest} 등)가 검증했고, 여기서는 엔티티가 만들 수 있는 잘못된 값 두 가지가
 * {@code saveAndFlush} 자리에서 그 제약으로 거절되는지 본다. 엔티티는 대상이 어느 호실의 것인지, 종류와 대상이 짝이 맞는지 모르고 DB가 최종으로 막는다.
 *
 * <p>위반 뒤 트랜잭션은 쓸 수 없으므로 사례마다 테스트(트랜잭션)를 나눈다({@link SchemaTestSupport}).
 */
class ResponseRequestIntegrityEntityTest extends SchemaTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-22T10:15:30.123456Z");
  private static final String FOREIGN_KEY_VIOLATION = "23503";
  private static final String CHECK_VIOLATION = "23514";

  @Autowired private ResponseRequestRepository requests;

  @Test
  void 다른_호실의_수리_건을_대상으로_하면_호실_복합_FK가_거절한다() {
    UUID roomA = room();
    UUID roomB = room();
    UUID repairInB = repair(roomB);
    // 대상은 실제로 있다(호실 B에). 그래서 이 거절은 "없는 ID"가 아니라 "다른 호실의 기록"이라서다
    assertThat(
            jdbc.queryForObject(
                "SELECT room_record_id FROM state.repair_case WHERE id = ?", UUID.class, repairInB))
        .isEqualTo(roomB);

    DataIntegrityViolationException error =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                requests.saveAndFlush(
                    new ResponseRequest(
                        roomA,
                        RequestKind.DEFECT_CONFIRM,
                        RequestTarget.of(TargetKind.REPAIR_CASE, repairInB),
                        PartySide.TENANT,
                        NOW)));

    assertViolation(error, FOREIGN_KEY_VIOLATION, "fk_response_request_repair_case_id_room");
  }

  @Test
  void 상황_변화_확인이_진행_상황이_아닌_대상을_가리키면_CHECK가_거절한다() {
    // V1의 이 CHECK는 이름 없이 만들어져 PostgreSQL이 이름을 붙였다. 위반 전에 정의로 찾아 둔다(위반 뒤에는 이 트랜잭션에서 조회할 수 없음).
    // kind 값 목록 CHECK에도 'SITUATION_CHECK' 글자가 있어서 progress_note_id까지 함께 든 것으로 좁힌다
    String situationCheck =
        jdbc.queryForObject(
            """
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'state.response_request'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%SITUATION_CHECK%'
              AND pg_get_constraintdef(oid) LIKE '%progress_note_id%'
            """,
            String.class);
    UUID room = room();
    UUID repair = repair(room);

    DataIntegrityViolationException error =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                requests.saveAndFlush(
                    new ResponseRequest(
                        room,
                        RequestKind.SITUATION_CHECK,
                        RequestTarget.of(TargetKind.REPAIR_CASE, repair),
                        PartySide.TENANT,
                        NOW)));

    assertViolation(error, CHECK_VIOLATION, situationCheck);
  }

  /** 문구 대신 SQLSTATE와 제약 이름으로 의도한 제약인지 본다. */
  private static void assertViolation(
      DataIntegrityViolationException error, String sqlState, String constraint) {
    SQLException sql = findCause(error, SQLException.class);
    // SQLException은 Throwable이면서 Iterable이라 형을 정해 준다
    assertThat((Throwable) sql).as("PostgreSQL 오류가 원인에 있어야 함").isNotNull();
    assertThat(sql.getSQLState()).isEqualTo(sqlState);
    ConstraintViolationException hibernate = findCause(error, ConstraintViolationException.class);
    assertThat(hibernate).as("Hibernate 제약 예외가 원인에 있어야 함").isNotNull();
    assertThat(hibernate.getConstraintName()).isEqualTo(constraint);
  }

  private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
    }
    return null;
  }
}
