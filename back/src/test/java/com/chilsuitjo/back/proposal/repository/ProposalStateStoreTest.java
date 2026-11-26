package com.chilsuitjo.back.proposal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import com.chilsuitjo.back.payment.service.FakePaymentService;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaState;
import com.chilsuitjo.back.proposal.rule.AgendaStatus;
import com.chilsuitjo.back.proposal.rule.ProposalState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 돈 안건 상태 읽기·쓰기({@code ProposalStateStore}). 실제 {@code repair_case}·{@code proposal}·{@code
 * proposal_line} 행으로 {@code load}가 head와 기다리는 안 요약을 맞게 만드는지, {@code save}가 head를 쓰고 응답 요청을 닫는지 본다.
 * 규칙은 {@code ProposalTransitionsTest} 몫이다.
 *
 * <p>응답 요청은 지금 가짜({@link FakeWaitService})라 DB가 아니라 가짜의 상태로 확인한다. 테스트는 끝나면 되돌린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties =
        "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public")
@Transactional
class ProposalStateStoreTest {

  @Autowired private ProposalStateStore store;
  @Autowired private FakePaymentService paymentService;
  @Autowired private FakeWaitService waits;
  @Autowired private JdbcTemplate jdbc;
  @PersistenceContext private EntityManager entityManager;

  @BeforeEach
  void region() {
    jdbc.update("INSERT INTO region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')");
  }

  @Test
  void 기다리는_안의_낸_측과_줄_금액의_합을_채운다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID proposal = proposal(room, repair, PartySide.LANDLORD, false);
    line(room, proposal, "LANDLORD", 60_000);
    line(room, proposal, "TENANT", 22_000);
    head(repair, "PENDING", proposal, null);
    paymentService.seedConfirmedTotal(room, repair, 82_000);

    ProposalState state = store.load(agree(room, proposal, PartySide.TENANT));

    assertThat(state.head())
        .isEqualTo(
            new AgendaState(
                room,
                new AgendaRef(AgendaKind.REPAIR_BURDEN, repair),
                AgendaStatus.PENDING,
                proposal,
                null,
                82_000L));
    assertThat(state.waiting())
        .isEqualTo(new ProposalState.Waiting(proposal, PartySide.LANDLORD, 82_000, false));
  }

  @Test
  void 옛_제안을_눌러도_기다리는_안은_head가_가리키는_안이다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID older = proposal(room, repair, PartySide.LANDLORD, false);
    UUID current = proposal(room, repair, PartySide.LANDLORD, false);
    line(room, current, "LANDLORD", 50_000);
    head(repair, "PENDING", current, null);

    ProposalState state = store.load(agree(room, older, PartySide.TENANT));

    assertThat(state.waiting().id()).isEqualTo(current);
    assertThat(state.waiting().total()).isEqualTo(50_000);
  }

  @Test
  void 기다리는_안이_없으면_waiting은_null이다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID agreed = proposal(room, repair, PartySide.LANDLORD, false);
    head(repair, "AGREED", null, agreed);

    ProposalState state = store.load(agree(room, agreed, PartySide.TENANT));

    assertThat(state.head().status()).isEqualTo(AgendaStatus.AGREED);
    assertThat(state.head().agreedProposalId()).isEqualTo(agreed);
    assertThat(state.waiting()).isNull();
  }

  @Test
  void 비용_없음_안은_줄이_없어_합계가_0이다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID proposal = proposal(room, repair, PartySide.TENANT, true);
    head(repair, "PENDING", proposal, null);

    ProposalState state = store.load(agree(room, proposal, PartySide.LANDLORD));

    assertThat(state.waiting())
        .isEqualTo(new ProposalState.Waiting(proposal, PartySide.TENANT, 0, true));
  }

  @Test
  void 다른_호실의_제안이면_NOT_FOUND다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID proposal = proposal(room, repair, PartySide.LANDLORD, false);
    UUID otherRoom = room();

    assertThatThrownBy(() -> store.load(agree(otherRoom, proposal, PartySide.TENANT)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void 없는_제안이면_NOT_FOUND다() {
    UUID room = room();

    assertThatThrownBy(() -> store.load(agree(room, UUID.randomUUID(), PartySide.TENANT)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void 동의를_저장하면_head를_쓰고_응답_요청을_답함으로_닫는다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID proposal = proposal(room, repair, PartySide.LANDLORD, false);
    line(room, proposal, "LANDLORD", 82_000);
    head(repair, "PENDING", proposal, null);
    RequestTarget agenda = RequestTarget.of(TargetKind.REPAIR_CASE, repair);
    waits.open(new OpenRequestCommand(room, RequestKind.PROPOSAL, agenda, PartySide.LANDLORD));
    ActionCommand command = agree(room, proposal, PartySide.TENANT);
    AgendaState now = store.load(command).head();
    AgendaState next =
        new AgendaState(
            room, now.agenda(), AgendaStatus.AGREED, null, proposal, now.requiredTotal());

    SavedState saved = store.save(command, new ProposalState(next, null));
    entityManager.flush();

    assertThat(saved).isEqualTo(SavedState.unchanged());
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT money_status, current_proposal_id, agreed_proposal_id"
                + " FROM repair_case WHERE id = ?",
            repair);
    assertThat(row.get("money_status")).isEqualTo("AGREED");
    assertThat(row.get("current_proposal_id")).isNull();
    assertThat(row.get("agreed_proposal_id")).isEqualTo(proposal);
    assertThat(waits.statusOf(agenda, RequestKind.PROPOSAL)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 이견을_저장하면_head를_쓰고_응답_요청을_답함으로_닫는다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID proposal = proposal(room, repair, PartySide.LANDLORD, false);
    head(repair, "PENDING", proposal, null);
    RequestTarget agenda = RequestTarget.of(TargetKind.REPAIR_CASE, repair);
    waits.open(new OpenRequestCommand(room, RequestKind.PROPOSAL, agenda, PartySide.LANDLORD));
    ActionCommand dispute = command(room, proposal, PartySide.TENANT, ActionType.DISPUTE_PROPOSAL);
    AgendaState now = store.load(dispute).head();
    AgendaState next =
        new AgendaState(room, now.agenda(), AgendaStatus.DISPUTE, null, null, now.requiredTotal());

    store.save(dispute, new ProposalState(next, null));
    entityManager.flush();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT money_status, current_proposal_id, agreed_proposal_id"
                + " FROM repair_case WHERE id = ?",
            repair);
    assertThat(row.get("money_status")).isEqualTo("DISPUTE");
    assertThat(row.get("current_proposal_id")).isNull();
    assertThat(row.get("agreed_proposal_id")).isNull();
    assertThat(waits.statusOf(agenda, RequestKind.PROPOSAL)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 아직_없는_행동은_저장하지_않고_head도_그대로다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID proposal = proposal(room, repair, PartySide.LANDLORD, false);
    head(repair, "PENDING", proposal, null);
    ActionCommand withdraw = command(room, proposal, PartySide.LANDLORD, ActionType.WITHDRAW);
    AgendaState now = store.load(withdraw).head();
    AgendaState next =
        new AgendaState(room, now.agenda(), AgendaStatus.NONE, null, null, now.requiredTotal());

    assertThatThrownBy(() -> store.save(withdraw, new ProposalState(next, null)))
        .isInstanceOf(IllegalStateException.class);
    entityManager.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT money_status FROM repair_case WHERE id = ?", String.class, repair))
        .isEqualTo("PENDING");
  }

  private static ActionCommand agree(UUID room, UUID proposal, PartySide side) {
    return command(room, proposal, side, ActionType.AGREE);
  }

  /** pipeline이 잠근 뒤 만드는 모양: 측이 채워져 있고 대상은 제안이다. */
  private static ActionCommand command(UUID room, UUID proposal, PartySide side, ActionType type) {
    return new ActionCommand(
        room,
        Actor.member(UUID.randomUUID()).withSide(side),
        type,
        "key",
        ActionTarget.of(TargetKind.PROPOSAL, proposal),
        null,
        null,
        null,
        Map.of());
  }

  private void head(UUID repair, String status, UUID current, UUID agreed) {
    jdbc.update(
        """
        UPDATE repair_case SET money_status = ?, current_proposal_id = ?, agreed_proposal_id = ?
        WHERE id = ?
        """,
        status,
        current,
        agreed,
        repair);
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

  private UUID proposal(UUID room, UUID repair, PartySide side, boolean noCost) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO proposal (id, room_record_id, repair_case_id, proposed_by_side, round,
          no_cost, created_at)
        VALUES (?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair,
        side.name(),
        noCost);
    return id;
  }

  private void line(UUID room, UUID proposal, String kind, long amount) {
    jdbc.update(
        """
        INSERT INTO proposal_line (id, room_record_id, proposal_id, line_kind, amount, created_at)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        room,
        proposal,
        kind,
        amount);
  }
}
