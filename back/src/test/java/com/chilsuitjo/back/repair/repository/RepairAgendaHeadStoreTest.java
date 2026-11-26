package com.chilsuitjo.back.repair.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.payment.service.FakePaymentService;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaState;
import com.chilsuitjo.back.proposal.rule.AgendaStatus;
import com.chilsuitjo.back.repair.rule.MoneyStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수리비 부담 안건 head 읽기·쓰기({@code RepairAgendaHeadStore}, decisions 2026-11-24). {@code repair_case}의 세
 * 칸과 {@code AgendaState}를 서로 바꾸는지, 기준 금액을 payment에서 채우는지, 잘못 부른 경우 거절하는지 본다. 규칙은 {@code
 * ProposalTransitions} 몫이라 여기서 보지 않는다.
 *
 * <p>제안 ID 칸은 V1 복합 FK({@code fk_repair_case_current_proposal}·{@code
 * fk_repair_case_agreed_proposal})가 있어 실제 {@code proposal} 행을 넣고 쓴다. 테스트는 끝나면 되돌린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties =
        "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public")
@Transactional
@ActiveProfiles("test")
class RepairAgendaHeadStoreTest {

  @Autowired private RepairAgendaHeadStore store;
  @Autowired private FakePaymentService paymentService;
  @Autowired private JdbcTemplate jdbc;
  @PersistenceContext private EntityManager entityManager;

  @BeforeEach
  void region() {
    jdbc.update("INSERT INTO region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')");
  }

  @Test
  void 수리비_부담_안건을_맡는다() {
    assertThat(store.kind()).isEqualTo(AgendaKind.REPAIR_BURDEN);
  }

  @Test
  void 막_등록한_수리_건은_head가_비어_있고_기준_금액은_payment에서_읽는다() {
    UUID room = room();
    UUID repair = repair(room);
    paymentService.seedConfirmedTotal(room, repair, 82_000);

    AgendaState state = store.load(room, repair);

    assertThat(state)
        .isEqualTo(
            new AgendaState(
                room,
                new AgendaRef(AgendaKind.REPAIR_BURDEN, repair),
                AgendaStatus.NONE,
                null,
                null,
                82_000L));
  }

  @Test
  void 확인된_지불이_없으면_기준_금액은_0이다() {
    UUID room = room();
    UUID repair = repair(room);

    assertThat(store.load(room, repair).requiredTotal()).isZero();
  }

  @Test
  void 제안_ID를_그대로_읽는다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID current = proposal(room, repair);
    UUID agreed = proposal(room, repair);
    jdbc.update(
        """
        UPDATE repair_case SET money_status = 'PENDING', current_proposal_id = ?,
          agreed_proposal_id = ? WHERE id = ?
        """,
        current,
        agreed,
        repair);

    AgendaState state = store.load(room, repair);

    assertThat(state.status()).isEqualTo(AgendaStatus.PENDING);
    assertThat(state.currentProposalId()).isEqualTo(current);
    assertThat(state.agreedProposalId()).isEqualTo(agreed);
  }

  @ParameterizedTest
  @CsvSource({
    "UNDECIDED, NONE",
    "PENDING, PENDING",
    "AGREED, AGREED",
    "NO_COST, NO_COST",
    "DISPUTE, DISPUTE"
  })
  void 돈_상태를_안건_상태로_바꿔_읽는다(MoneyStatus stored, AgendaStatus expected) {
    UUID room = room();
    UUID repair = repair(room);
    jdbc.update("UPDATE repair_case SET money_status = ? WHERE id = ?", stored.name(), repair);

    assertThat(store.load(room, repair).status()).isEqualTo(expected);
  }

  @Test
  void 다른_호실의_수리_건은_NOT_FOUND다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID otherRoom = room();

    assertThatThrownBy(() -> store.load(otherRoom, repair))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void 없는_수리_건은_NOT_FOUND다() {
    UUID room = room();

    assertThatThrownBy(() -> store.load(room, UUID.randomUUID()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void 읽은_head를_save만_불러도_commit_때_세_칸이_UPDATE된다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID current = proposal(room, repair);
    UUID agreed = proposal(room, repair);
    AgendaState loaded = store.load(room, repair);

    store.save(next(loaded, AgendaStatus.PENDING, current, agreed));
    entityManager.flush();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT money_status, current_proposal_id, agreed_proposal_id, version"
                + " FROM repair_case WHERE id = ?",
            repair);
    assertThat(row.get("money_status")).isEqualTo("PENDING");
    assertThat(row.get("current_proposal_id")).isEqualTo(current);
    assertThat(row.get("agreed_proposal_id")).isEqualTo(agreed);
    assertThat(row.get("version")).isEqualTo(1L);
  }

  @Test
  void 제안_ID를_null로_쓰면_칸을_비운다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID current = proposal(room, repair);
    UUID agreed = proposal(room, repair);
    jdbc.update(
        """
        UPDATE repair_case SET money_status = 'AGREED', current_proposal_id = ?,
          agreed_proposal_id = ? WHERE id = ?
        """,
        current,
        agreed,
        repair);
    AgendaState loaded = store.load(room, repair);

    store.save(next(loaded, AgendaStatus.NONE, null, null));
    entityManager.flush();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT money_status, current_proposal_id, agreed_proposal_id"
                + " FROM repair_case WHERE id = ?",
            repair);
    assertThat(row.get("money_status")).isEqualTo("UNDECIDED");
    assertThat(row.get("current_proposal_id")).isNull();
    assertThat(row.get("agreed_proposal_id")).isNull();
  }

  @Test
  void 쓴_head를_다시_읽으면_같은_값이다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID agreed = proposal(room, repair);
    paymentService.seedConfirmedTotal(room, repair, 50_000);
    AgendaState next = next(store.load(room, repair), AgendaStatus.AGREED, null, agreed);

    store.save(next);
    entityManager.flush();
    entityManager.clear();

    assertThat(store.load(room, repair)).isEqualTo(next);
  }

  @ParameterizedTest
  @CsvSource({
    "NONE, UNDECIDED",
    "PENDING, PENDING",
    "AGREED, AGREED",
    "NO_COST, NO_COST",
    "DISPUTE, DISPUTE"
  })
  void 안건_상태를_돈_상태로_바꿔_쓴다(AgendaStatus next, MoneyStatus expected) {
    UUID room = room();
    UUID repair = repair(room);
    AgendaState loaded = store.load(room, repair);

    store.save(next(loaded, next, null, null));
    entityManager.flush();

    assertThat(
            jdbc.queryForObject(
                "SELECT money_status FROM repair_case WHERE id = ?", String.class, repair))
        .isEqualTo(expected.name());
  }

  @Test
  void EXCLUDED는_수리비_부담에_없어_거절한다() {
    UUID room = room();
    UUID repair = repair(room);
    AgendaState loaded = store.load(room, repair);

    assertThatThrownBy(() -> store.save(next(loaded, AgendaStatus.EXCLUDED, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 다른_종류의_안건은_거절한다() {
    UUID room = room();
    UUID repair = repair(room);
    AgendaState settlementItem =
        new AgendaState(
            room,
            new AgendaRef(AgendaKind.SETTLEMENT_ITEM, repair),
            AgendaStatus.PENDING,
            null,
            null,
            null);

    assertThatThrownBy(() -> store.save(settlementItem))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 다른_호실의_수리_건에_쓰려_하면_코드_버그로_거절한다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID otherRoom = room();
    AgendaState wrongRoom =
        new AgendaState(
            otherRoom,
            new AgendaRef(AgendaKind.REPAIR_BURDEN, repair),
            AgendaStatus.NO_COST,
            null,
            null,
            0L);

    assertThatThrownBy(() -> store.save(wrongRoom)).isInstanceOf(IllegalStateException.class);
    entityManager.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT money_status FROM repair_case WHERE id = ?", String.class, repair))
        .isEqualTo("UNDECIDED");
  }

  @Test
  void 없는_수리_건에_쓰려_하면_코드_버그로_거절한다() {
    UUID room = room();
    AgendaState missing =
        new AgendaState(
            room,
            new AgendaRef(AgendaKind.REPAIR_BURDEN, UUID.randomUUID()),
            AgendaStatus.NO_COST,
            null,
            null,
            0L);

    assertThatThrownBy(() -> store.save(missing)).isInstanceOf(IllegalStateException.class);
  }

  private static AgendaState next(
      AgendaState from, AgendaStatus status, UUID currentProposalId, UUID agreedProposalId) {
    return new AgendaState(
        from.roomRecordId(),
        from.agenda(),
        status,
        currentProposalId,
        agreedProposalId,
        from.requiredTotal());
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

  private UUID proposal(UUID room, UUID repair) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO proposal (id, room_record_id, repair_case_id, proposed_by_side, round,
          created_at)
        VALUES (?, ?, ?, 'TENANT', 1, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair);
    return id;
  }
}
