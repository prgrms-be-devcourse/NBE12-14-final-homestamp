package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 응답 요청 엔티티가 V1 {@code state.response_request}와 맞는지 본다(#117 1단계). 앱이 뜰 때 {@code ddl-auto:
 * validate}가 칸 이름·타입을 확인하고, 여기서는 실제로 INSERT한 뒤 다시 읽어 값이 그대로인지 본다.
 *
 * <p>{@code saveAndFlush}로 그 자리에서 INSERT를 보내고, {@code clear}로 1차 캐시를 비워 다시 읽을 때 DB에서 가져오게 한다. 테스트
 * 트랜잭션은 끝나면 되돌린다({@link SchemaTestSupport}).
 */
class ResponseRequestEntityTest extends SchemaTestSupport {

  /** DB {@code timestamptz}는 마이크로초까지라 그 아래가 없는 값을 쓴다. */
  private static final Instant NOW = Instant.parse("2026-11-22T10:15:30.123456Z");

  @Autowired private ResponseRequestRepository requests;
  @PersistenceContext private EntityManager entityManager;

  @Test
  void 호실_기록이_대상인_요청을_저장하고_다시_읽는다() {
    UUID room = room();

    ResponseRequest saved =
        requests.saveAndFlush(
            new ResponseRequest(
                room,
                RequestKind.END_REQUEST,
                RequestTarget.roomRecord(room),
                PartySide.LANDLORD,
                NOW));
    entityManager.clear();

    ResponseRequest found = requests.findById(saved.getId()).orElseThrow();
    assertThat(found).isNotSameAs(saved);
    assertThat(found.getId().version()).isEqualTo(7);
    assertThat(found.getRoomRecordId()).isEqualTo(room);
    assertThat(found.getKind()).isEqualTo(RequestKind.END_REQUEST);
    assertThat(found.target()).isEqualTo(RequestTarget.roomRecord(room));
    assertThat(found.getRequestedBySide()).isEqualTo(PartySide.LANDLORD);
    assertThat(found.getStatus()).isEqualTo(RequestStatus.WAITING);
    assertThat(found.getCreatedAt()).isEqualTo(NOW);
    assertThat(found.getVersion()).isZero();
    // 만들 때는 전달 증거가 없으니 기한도 비어 있다(03 1-1)
    assertThat(found.getFirstNotifiedAt()).isNull();
    assertThat(found.getRenotifyDueAt()).isNull();
    assertThat(found.getUnconfirmedDueAt()).isNull();
    assertThat(found.getClosedReason()).isNull();
    assertThat(found.getClosedAt()).isNull();
    assertThat(found.getSoloReason()).isNull();
  }

  @Test
  void 수리_건이_대상인_요청은_그_칸에만_ID가_들어간다() {
    UUID room = room();
    UUID repair = repair(room);

    ResponseRequest saved =
        requests.saveAndFlush(
            new ResponseRequest(
                room,
                RequestKind.DEFECT_CONFIRM,
                RequestTarget.of(TargetKind.REPAIR_CASE, repair),
                PartySide.TENANT,
                NOW));
    entityManager.clear();

    assertThat(requests.findById(saved.getId()).orElseThrow().target())
        .isEqualTo(RequestTarget.of(TargetKind.REPAIR_CASE, repair));

    // enum은 이름 글자로 저장되고(V1 CHECK 값과 같음) 다른 대상 칸은 비어 있다
    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM state.response_request WHERE id = ?", saved.getId());
    assertThat(row)
        .containsEntry("kind", "DEFECT_CONFIRM")
        .containsEntry("status", "WAITING")
        .containsEntry("requested_by_side", "TENANT")
        .containsEntry("repair_case_id", repair)
        .containsEntry("payment_id", null)
        .containsEntry("settlement_item_id", null)
        .containsEntry("settlement_id", null)
        .containsEntry("inspection_id", null)
        .containsEntry("inspection_item_id", null)
        .containsEntry("settlement_transfer_id", null)
        .containsEntry("progress_note_id", null)
        .containsEntry("version", 0L);
  }
}
