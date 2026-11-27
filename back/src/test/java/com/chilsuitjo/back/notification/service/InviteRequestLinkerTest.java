package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.RequestContextTestSupport;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.AwaitingConfirm;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 초대에 점검 확인 요청 잇기(#370 A-7)를 실제 DB로 본다. 점검 읽기 포트는 inspection(A)이 구현할 것이라 이 테스트에서만 호실별 목록을 돌려주는 대역을
 * 쓴다(운영 빈 없음).
 */
@Import({TestcontainersConfiguration.class, InviteRequestLinkerTest.Inspections.class})
@SpringBootTest(properties = "test.notification.wait-service=jpa")
@ActiveProfiles("test")
class InviteRequestLinkerTest {

  /** 호실마다 확인 요청 상태 점검. 테스트 전용. */
  @TestConfiguration
  static class Inspections {
    static final Map<UUID, List<AwaitingConfirm>> BY_ROOM = new ConcurrentHashMap<>();

    @Bean
    InspectionsAwaitingConfirm inspectionsAwaitingConfirm() {
      return room -> BY_ROOM.getOrDefault(room, List.of());
    }
  }

  private static final Instant AT = Instant.parse("2026-11-27T01:02:03.456789Z");

  @Autowired private InviteRequestLinker linker;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private NoticeWaitStart waitStart;
  @Autowired private NoticeRepository notices;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private ActionRequestContext requestContext;

  @Test
  void 같은_점검을_초대하면_초대_문자_한_통에_새_확인_요청이_붙고_아직_기다리지_않는다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.TENANT));

    UUID notice = invite(room, inspectionTarget(inspection));

    List<Map<String, Object>> rows = notices(room.id());
    assertThat(rows).hasSize(1);
    Map<String, Object> invite = rows.get(0);
    assertThat(invite).containsEntry("id", notice).containsEntry("kind", "INVITE");
    UUID requestId = (UUID) invite.get("response_request_id");
    assertThat(requestId).isNotNull();
    assertThat(invite.get("link_id")).isEqualTo(linkOf(room, inspection));
    Map<String, Object> request = request(requestId);
    assertThat(request)
        .containsEntry("kind", "INSPECTION_CONFIRM")
        .containsEntry("inspection_id", inspection)
        .containsEntry("requested_by_side", "TENANT")
        .containsEntry("status", "WAITING");
    assertThat(request.get("first_notified_at")).isNull();
    assertThat(outboxCount(room.id())).isEqualTo(1);
  }

  @Test
  void 호실_초대면_다른_점검은_그_점검_링크로_요청_통지를_따로_넣고_초대_문자에는_요청이_없다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.TENANT));

    invite(room, null);

    List<Map<String, Object>> rows = notices(room.id());
    assertThat(rows).extracting(r -> r.get("kind")).containsExactlyInAnyOrder("INVITE", "REQUEST");
    Map<String, Object> invite = byKind(rows, "INVITE");
    Map<String, Object> request = byKind(rows, "REQUEST");
    assertThat(invite.get("response_request_id")).isNull();
    assertThat(invite.get("link_id")).isEqualTo(roomLinkOf(room));
    assertThat(request.get("response_request_id")).isNotNull();
    // 요청 통지는 호실 초대 링크가 아니라 그 점검의 링크
    assertThat(request.get("link_id"))
        .isEqualTo(linkOf(room, inspection))
        .isNotEqualTo(invite.get("link_id"));
    assertThat(outboxCount(room.id())).isEqualTo(2);
  }

  @Test
  void 받는_자리가_요청한_점검은_잇지_않는다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.LANDLORD));

    invite(room, inspectionTarget(inspection));

    assertThat(notices(room.id()))
        .singleElement()
        .satisfies(n -> assertThat(n.get("response_request_id")).isNull());
    assertThat(requestCount(room.id())).isZero();
  }

  @Test
  void 다시_초대하면_같은_링크와_같은_요청에_통지를_보완하고_요청은_늘지_않는다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.TENANT));

    invite(room, inspectionTarget(inspection));
    invite(room, inspectionTarget(inspection));

    List<Map<String, Object>> rows = notices(room.id());
    assertThat(rows).hasSize(2).allSatisfy(n -> assertThat(n.get("kind")).isEqualTo("INVITE"));
    assertThat(rows.get(0).get("response_request_id"))
        .isEqualTo(rows.get(1).get("response_request_id"));
    assertThat(rows.get(0).get("link_id")).isEqualTo(rows.get(1).get("link_id"));
    assertThat(requestCount(room.id())).isEqualTo(1);
  }

  @Test
  void 다른_점검에_아직_닿지_않은_요청_통지가_있으면_다시_초대해도_그_문자를_또_넣지_않는다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.TENANT));

    invite(room, null);
    invite(room, null);

    List<Map<String, Object>> rows = notices(room.id());
    assertThat(rows).filteredOn(r -> "REQUEST".equals(r.get("kind"))).hasSize(1);
    assertThat(rows).filteredOn(r -> "INVITE".equals(r.get("kind"))).hasSize(2);
  }

  @Test
  void 호실_초대_뒤_그_점검으로_다시_초대하면_요청_통지는_늘지_않고_초대_문자에만_요청이_걸린다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.TENANT));

    UUID roomInvite = invite(room, null);
    UUID inspectionInvite = invite(room, inspectionTarget(inspection));

    List<Map<String, Object>> rows = notices(room.id());
    // 문자는 초대 두 통 + 처음 요청 통지 한 통뿐이다(요청을 초대 문자에 거는 것은 문자를 늘리지 않음)
    assertThat(rows).hasSize(3);
    assertThat(rows).filteredOn(r -> "REQUEST".equals(r.get("kind"))).hasSize(1);
    UUID requestId = (UUID) byKind(rows, "REQUEST").get("response_request_id");
    // 같은 시각에 만들어져 순서로 고르지 않고, onInvite가 돌려준 초대 문자 ID로 고른다
    assertThat(notice(rows, roomInvite)).containsEntry("kind", "INVITE");
    assertThat(notice(rows, roomInvite).get("response_request_id")).isNull();
    assertThat(notice(rows, inspectionInvite))
        .containsEntry("kind", "INVITE")
        .containsEntry("response_request_id", requestId);
    assertThat(requestCount(room.id())).isEqualTo(1);
  }

  @Test
  void 미확인이_된_요청에는_통지를_더하지_않는다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.TENANT));
    invite(room, null);
    jdbc.update(
        "UPDATE state.response_request SET status = 'UNCONFIRMED' WHERE inspection_id = ?",
        inspection);
    jdbc.update(
        "UPDATE state.notice SET status = 'CANCELLED' WHERE room_record_id = ? AND kind = 'REQUEST'",
        room.id());

    invite(room, null);

    assertThat(notices(room.id())).filteredOn(r -> "REQUEST".equals(r.get("kind"))).hasSize(1);
    assertThat(requestCount(room.id())).isEqualTo(1);
  }

  @Test
  void 요청이_걸린_초대_문자가_닿아야_기다림이_시작되고_시작된_뒤의_초대에는_요청을_붙이지_않는다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    awaiting(room.id(), new AwaitingConfirm(inspection, PartySide.TENANT));
    UUID first = invite(room, inspectionTarget(inspection));
    UUID requestId = (UUID) notices(room.id()).get(0).get("response_request_id");
    Instant delivered = AT.plusSeconds(120);

    boolean started =
        inTx(() -> waitStart.reached(notices.findById(first).orElseThrow(), delivered));

    assertThat(started).isTrue();
    assertThat(((java.sql.Timestamp) request(requestId).get("first_notified_at")).toInstant())
        .isEqualTo(delivered);

    UUID again = invite(room, inspectionTarget(inspection));
    assertThat(
            jdbc.queryForMap("SELECT * FROM state.notice WHERE id = ?", again)
                .get("response_request_id"))
        .isNull();
  }

  @Test
  void 요청이_없는_초대_문자가_닿아도_기다림과_상관없다() {
    Room room = room();
    UUID plain = invite(room, null);

    boolean started = inTx(() -> waitStart.reached(notices.findById(plain).orElseThrow(), AT));

    assertThat(started).isFalse();
  }

  @Test
  void 도중에_실패하면_초대_링크_문자와_요청이_함께_되돌아간다() {
    Room room = room();
    Room other = room();
    UUID foreign = inspection(other.id()); // 다른 호실의 점검: 요청 저장(복합 외래 키)에서 실패
    awaiting(room.id(), new AwaitingConfirm(foreign, PartySide.TENANT));

    assertThatThrownBy(() -> invite(room, null)).isInstanceOf(RuntimeException.class);

    assertThat(notices(room.id())).isEmpty();
    assertThat(requestCount(room.id())).isZero();
    assertThat(outboxCount(room.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.link WHERE room_record_id = ?",
                Integer.class,
                room.id()))
        .isZero();
  }

  @Test
  void 점검_읽기_포트가_없으면_초대를_보내지_않고_예외다() {
    InviteRequestLinker withoutPort =
        new InviteRequestLinker(
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(InspectionsAwaitingConfirm.class),
            null,
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() ->
            withoutPort.onInvite(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }

  private UUID invite(Room room, RequestTarget record) {
    return inTx(() -> {
      UUID link = inviteLinks.issueInvite(room.id(), room.counterpart(), record, room.issuer());
      return linker.onInvite(room.id(), room.counterpart(), record, link, room.issuer());
    });
  }

  private <T> T inTx(Supplier<T> work) {
    return new TransactionTemplate(txManager)
        .execute(status -> RequestContextTestSupport.within(requestContext, AT, work));
  }

  private static void awaiting(UUID room, AwaitingConfirm... list) {
    Inspections.BY_ROOM.put(room, List.of(list));
  }

  private static RequestTarget inspectionTarget(UUID inspection) {
    return RequestTarget.of(TargetKind.INSPECTION, inspection);
  }

  private List<Map<String, Object>> notices(UUID room) {
    return jdbc.queryForList(
        "SELECT * FROM state.notice WHERE room_record_id = ? ORDER BY created_at, kind", room);
  }

  private static Map<String, Object> notice(List<Map<String, Object>> rows, UUID id) {
    return rows.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
  }

  private static Map<String, Object> byKind(List<Map<String, Object>> rows, String kind) {
    return rows.stream().filter(r -> kind.equals(r.get("kind"))).findFirst().orElseThrow();
  }

  private Map<String, Object> request(UUID id) {
    return jdbc.queryForMap("SELECT * FROM state.response_request WHERE id = ?", id);
  }

  private int requestCount(UUID room) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.response_request WHERE room_record_id = ?",
        Integer.class,
        room);
  }

  private int outboxCount(UUID room) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.outbox o JOIN state.notice n ON o.dedupe_key = 'sms:' || n.id"
            + " WHERE n.room_record_id = ?",
        Integer.class,
        room);
  }

  private UUID linkOf(Room room, UUID inspection) {
    return jdbc.queryForObject(
        "SELECT id FROM state.link WHERE room_record_id = ? AND party_id = ? AND inspection_id = ?",
        UUID.class,
        room.id(),
        room.counterpart(),
        inspection);
  }

  private UUID roomLinkOf(Room room) {
    return jdbc.queryForObject(
        "SELECT id FROM state.link WHERE room_record_id = ? AND party_id = ?"
            + " AND inspection_id IS NULL AND repair_case_id IS NULL",
        UUID.class,
        room.id(),
        room.counterpart());
  }

  private Room room() {
    UUID id = UUID.randomUUID();
    String region = jdbc.queryForObject("SELECT code FROM state.region LIMIT 1", String.class);
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version)"
            + " VALUES (?, ?, '테스트', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        id,
        region);
    UUID issuer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        issuer);
    UUID mine = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, member_id, status, created_at, version)"
            + " VALUES (?, ?, 'TENANT', ?, 'CONNECTED', CURRENT_TIMESTAMP, 0)",
        mine,
        id,
        issuer);
    UUID counterpart = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, invite_phone, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', '01012345678', 'INVITED', CURRENT_TIMESTAMP, 0)",
        counterpart,
        id);
    return new Room(id, mine, counterpart, issuer);
  }

  private UUID inspection(UUID room) {
    return inspection(room, "MOVE_IN");
  }

  private UUID inspection(UUID room, String kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.inspection (id, room_record_id, kind, author_side, status, created_at, version)"
            + " VALUES (?, ?, ?, 'TENANT', 'DRAFT', CURRENT_TIMESTAMP, 0)",
        id,
        room,
        kind);
    return id;
  }

  private record Room(UUID id, UUID mine, UUID counterpart, UUID issuer) {}
}
