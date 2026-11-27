package com.chilsuitjo.back.repair.service;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.repair.dto.StaleRepairCase;
import com.chilsuitjo.back.repair.rule.StaleReason;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 홈 "오래 멈춘 건" 창구({@link RepairListService#stale}, 06 REP-6a·6e, #370 B-2)를 진짜 DB와 pipeline으로 본다. 서버
 * 시계를 옮겨 행동 기록 시각과 조회 시각을 함께 움직인다. 기간의 정확한 경계는 {@code StaleRuleTest}가 본다.
 */
@Import({TestcontainersConfiguration.class, RepairStaleTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairStaleTest {

  private static final Duration STALE = Duration.ofDays(14).plusMinutes(1);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RepairListService service;
  @Autowired private MutableClock clock;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    clock.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = insertRoom();
    tenant = member();
    landlord = member();
    part = part();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
  }

  @Test
  void 처리_중인_건은_기간이_지나면_보이고_그_전에는_없다() throws Exception {
    UUID caseId = register("천장 누수");
    act(caseId, tenant, "start").andExpect(status().isOk());

    clock.advance(Duration.ofDays(13));
    assertThat(service.stale(room, tenant)).isEmpty();

    clock.advance(Duration.ofDays(2));
    List<StaleRepairCase> stale = service.stale(room, landlord);
    assertThat(stale).hasSize(1);
    StaleRepairCase line = stale.getFirst();
    assertThat(line.repairCaseId()).isEqualTo(caseId);
    assertThat(line.no()).isEqualTo(1);
    assertThat(line.title()).isEqualTo("천장 누수");
    assertThat(line.partName()).isEqualTo("천장");
    assertThat(line.reason()).isEqualTo(StaleReason.IN_PROGRESS);
    assertThat(line.lastActionAt()).isEqualTo(lastActionAt(caseId));
  }

  @Test
  void 시작_전_보충_요청됨은_보류로_보이고_확인_전_시작_전은_없다() throws Exception {
    UUID held = register("보류된 하자");
    respond(held, landlord, "NEED_MORE").andExpect(status().isOk());
    register("아직 확인 전");

    clock.advance(STALE);

    assertThat(service.stale(room, tenant))
        .singleElement()
        .satisfies(line -> {
          assertThat(line.repairCaseId()).isEqualTo(held);
          assertThat(line.reason()).isEqualTo(StaleReason.NEED_MORE);
        });
  }

  @Test
  void 종료_확인_대기와_처리_완료는_없다() throws Exception {
    UUID waiting = register("종료 확인 대기");
    act(waiting, tenant, "start").andExpect(status().isOk());
    act(waiting, tenant, "request-close").andExpect(status().isOk());
    UUID done = register("처리 완료");
    act(done, tenant, "start").andExpect(status().isOk());
    act(done, tenant, "request-close").andExpect(status().isOk());
    act(done, landlord, "confirm-close").andExpect(status().isOk());

    clock.advance(STALE);

    assertThat(service.stale(room, tenant)).isEmpty();
  }

  @Test
  void 새_당사자_행동이_있으면_그때부터_다시_센다() throws Exception {
    UUID caseId = register("천장 누수");
    act(caseId, tenant, "start").andExpect(status().isOk());
    clock.advance(Duration.ofDays(10));
    noteProgress(caseId, landlord).andExpect(status().isCreated());

    clock.advance(Duration.ofDays(10));
    assertThat(service.stale(room, tenant)).isEmpty();

    clock.advance(Duration.ofDays(5));
    assertThat(service.stale(room, tenant))
        .extracting(StaleRepairCase::repairCaseId)
        .containsExactly(caseId);
  }

  @Test
  void 마지막_행동이_오래된_것부터다() throws Exception {
    UUID older = register("먼저 멈춘 건");
    act(older, tenant, "start").andExpect(status().isOk());
    clock.advance(Duration.ofDays(1));
    UUID newer = register("나중에 멈춘 건");
    act(newer, tenant, "start").andExpect(status().isOk());

    clock.advance(STALE);

    assertThat(service.stale(room, tenant))
        .extracting(StaleRepairCase::repairCaseId)
        .containsExactly(older, newer);
  }

  @Test
  void 공유_중단_제한이_살아_있으면_빈_목록이다() throws Exception {
    UUID caseId = register("천장 누수");
    act(caseId, tenant, "start").andExpect(status().isOk());
    clock.advance(STALE);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        Timestamp.from(clock.instant()),
        room);

    // 그만둔 사람도, 남은 사람도(묻기가 안 된다)
    assertThat(service.stale(room, landlord)).isEmpty();
    assertThat(service.stale(room, tenant)).isEmpty();
  }

  @Test
  void 호실_당사자가_아니면_NOT_FOUND다() {
    assertThatThrownBy(() -> service.stale(room, member()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  // ---- 준비물 ----

  private Instant lastActionAt(UUID caseId) {
    return jdbc.queryForObject(
            "SELECT max(created_at) FROM record.action WHERE repair_case_id = ?",
            Timestamp.class,
            caseId)
        .toInstant();
  }

  private UUID register(String title) throws Exception {
    String body =
        mvc.perform(
                post("/api/rooms/{id}/repair-cases", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"roomPartId\":\""
                            + part
                            + "\",\"title\":\""
                            + title
                            + "\",\"uploadTickets\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private ResultActions respond(UUID caseId, UUID who, String answer) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/respond", caseId)
            .with(loginAs(who))
            .header("Idempotency-Key", "res-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":\"" + answer + "\",\"seenVersionNo\":1,\"reason\":\"사진이 흐려요\"}"));
  }

  private ResultActions noteProgress(UUID caseId, UUID who) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/progress-notes", caseId)
            .with(loginAs(who))
            .header("Idempotency-Key", "note-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"업체를 다시 불렀어요\"}"));
  }

  private ResultActions act(UUID caseId, UUID who, String action) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(who))
            .header("Idempotency-Key", action + "-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  private UUID insertRoom() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private void seat(PartySide side, UUID memberId) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side.name(),
        memberId);
  }

  /** 기본 목록(V7)에서 복사된 "방 › 천장". */
  private UUID part() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone = '방' AND t.name = '천장'
        """,
        id,
        room);
    return id;
  }

  /** 서버 시각(pipeline·조회가 쓰는 {@code Clock}). 실제 시각에 옮긴 만큼을 더한 흐르는 시계다. 테스트마다 0으로 되돌린다. */
  static class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    void reset() {
      offset.set(Duration.ZERO);
    }

    void advance(Duration duration) {
      offset.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.now().plus(offset.get());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }
}
