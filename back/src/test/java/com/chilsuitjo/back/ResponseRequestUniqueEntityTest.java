package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 응답 요청을 엔티티로 저장할 때 V1 유니크({@code ux_response_request_waiting})가 같은 대상·종류의 두 번째 WAITING을 막는지
 * 본다(#117 3단계). 유니크 자체는 SQL 테스트({@code ResponseRequestSchemaTest})가 이미 검증했고, 여기서는 JPA가 만든 INSERT도
 * 같은 제약에 걸려 {@code saveAndFlush} 자리에서 Spring의 {@link DataIntegrityViolationException}으로 올라오는지 본다.
 *
 * <p>위반이 나면 PostgreSQL은 그 트랜잭션을 되돌리기만 할 수 있는 상태로 둔다. 테스트 메서드마다 트랜잭션이 따로라({@link SchemaTestSupport}의
 * {@code @Transactional}) 위반 뒤에 같은 트랜잭션으로 더 쓰지 않는다.
 */
class ResponseRequestUniqueEntityTest extends SchemaTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-22T10:15:30.123456Z");
  private static final String WAITING_UNIQUE = "ux_response_request_waiting";
  private static final String UNIQUE_VIOLATION = "23505";

  @Autowired private ResponseRequestRepository requests;

  @Test
  void 대상_칸이_모두_비어도_같은_호실의_END_REQUEST_WAITING은_하나뿐이다() {
    // V1 인덱스는 NULLS NOT DISTINCT라 대상 칸이 모두 NULL이어도 같은 값으로 비교한다(기본 동작이면 NULL끼리 달라 둘 다 들어감)
    UUID room = room();
    requests.saveAndFlush(endRequest(room, PartySide.LANDLORD));

    DataIntegrityViolationException error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> requests.saveAndFlush(endRequest(room, PartySide.LANDLORD)));

    assertUniqueViolation(error, WAITING_UNIQUE);
    // 위반 뒤 이 트랜잭션은 실패 상태다(25P02). 그래서 위반 사례마다 테스트를 나눈다
    assertThatThrownBy(() -> jdbc.queryForObject("SELECT 1", Integer.class))
        .isInstanceOf(DataAccessException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("25P02"));
  }

  @Test
  void 같은_수리_건의_DEFECT_CONFIRM_WAITING은_요청한_측이_달라도_하나뿐이다() {
    // 인덱스에 requested_by_side가 없다. 이 동작이 업무 규칙과 맞는지는 종류마다 다를 수 있다(CORRECTION_CONFIRM은 #117에서 질문)
    UUID room = room();
    UUID repair = repair(room);
    requests.saveAndFlush(defectConfirm(room, repair, PartySide.TENANT));

    DataIntegrityViolationException error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> requests.saveAndFlush(defectConfirm(room, repair, PartySide.LANDLORD)));

    assertUniqueViolation(error, WAITING_UNIQUE);
  }

  @Test
  void 같은_수리_건이라도_종류가_다르면_WAITING이_함께_있다() {
    // 위 두 거절이 "아무 INSERT나 막는" 것이 아님을 보이는 대조. 한 수리 건에 하자 확인과 종료 확인이 따로 있다(decisions 2026-11-19)
    UUID room = room();
    UUID repair = repair(room);
    requests.saveAndFlush(defectConfirm(room, repair, PartySide.TENANT));

    requests.saveAndFlush(
        new ResponseRequest(
            room,
            RequestKind.CLOSE_CONFIRM,
            RequestTarget.of(TargetKind.REPAIR_CASE, repair),
            PartySide.TENANT,
            NOW));

    assertThat(requests.count()).isEqualTo(2);
  }

  private static ResponseRequest endRequest(UUID room, PartySide side) {
    return new ResponseRequest(
        room, RequestKind.END_REQUEST, RequestTarget.roomRecord(room), side, NOW);
  }

  private static ResponseRequest defectConfirm(UUID room, UUID repair, PartySide side) {
    return new ResponseRequest(
        room,
        RequestKind.DEFECT_CONFIRM,
        RequestTarget.of(TargetKind.REPAIR_CASE, repair),
        side,
        NOW);
  }

  /** 문구 대신 원인 예외에 든 SQLSTATE와 제약 이름으로 본다. */
  private static void assertUniqueViolation(DataIntegrityViolationException error, String name) {
    assertThat(sqlState(error)).isEqualTo(UNIQUE_VIOLATION);
    assertThat(error)
        .rootCause()
        .isInstanceOf(SQLException.class); // PostgreSQL 드라이버가 낸 원래 오류까지 이어져 있다
    ConstraintViolationException hibernate = findCause(error, ConstraintViolationException.class);
    assertThat(hibernate).as("Hibernate 제약 예외가 원인에 있어야 함").isNotNull();
    assertThat(hibernate.getConstraintName()).isEqualTo(name);
  }

  private static String sqlState(Throwable error) {
    SQLException sql = findCause(error, SQLException.class);
    return sql == null ? null : sql.getSQLState();
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
