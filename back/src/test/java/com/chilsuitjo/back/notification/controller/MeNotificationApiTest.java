package com.chilsuitjo.back.notification.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.notification.dto.InfoCommand;
import com.chilsuitjo.back.notification.rule.InAppTopic;
import com.chilsuitjo.back.notification.service.InAppNotifier;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서비스 안 알림(06 4-1 NOTI-7, #116 U3-1): 실제 {@code InAppNotifier}가 원인 트랜잭션 안에서 PostgreSQL에 남기고, 목록·안
 * 읽은 수·읽음 API는 로그인한 회원의 만료 전 알림만 다룬다. 응답 칸은 이 작업에서 정했다(06에 칸이 없음).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeNotificationApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InAppNotifier notifier;
  @Autowired private TransactionTemplate transaction;

  private UUID me;
  private UUID other;

  @BeforeEach
  void setUp() {
    me = member();
    other = member();
  }

  @Test
  void 번호_인증_해제_알림은_계정_알림으로_저장되고_30일_뒤_만료된다() throws Exception {
    notifyReleased(me);

    var row =
        jdbc.queryForMap(
            "SELECT kind, topic, room_record_id, target_kind, group_key, read_at,"
                + " expires_at - created_at AS ttl FROM state.in_app_notification WHERE member_id = ?",
            me);
    assertThat(row)
        .containsEntry("kind", "SERVICE")
        .containsEntry("topic", "PHONE_VERIFICATION_RELEASED")
        .containsEntry("room_record_id", null)
        .containsEntry("target_kind", null)
        .containsEntry("group_key", null)
        .containsEntry("read_at", null);
    assertThat(row.get("ttl").toString()).isEqualTo("30 days");
  }

  @Test
  void 원인_트랜잭션_밖에서는_남기지_않고_원인이_되돌아가면_알림도_사라진다() {
    assertThatThrownBy(
            () -> notifier.notify(InfoCommand.account(me, InAppTopic.PHONE_VERIFICATION_RELEASED)))
        .isInstanceOf(IllegalTransactionStateException.class);

    transaction.executeWithoutResult(status -> {
      notifier.notify(InfoCommand.account(me, InAppTopic.PHONE_VERIFICATION_RELEASED));
      status.setRollbackOnly();
    });

    assertThat(count(me)).isZero();
  }

  @Test
  void 계정_알림에_호실을_주면_만들_때_거절한다() {
    assertThatThrownBy(() ->
            new InfoCommand(me, UUID.randomUUID(), InAppTopic.PHONE_VERIFICATION_RELEASED, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void DB는_주제_없는_알려_줌과_주제_있는_응답_필요를_막는다() {
    assertThatThrownBy(() -> insertRaw(me, "INFO", null, Instant.now().plusSeconds(60)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            insertRaw(me, "RESPONSE", "PHONE_VERIFICATION_RELEASED", Instant.now().plusSeconds(60)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> insertRaw(me, "SERVICE", "UNKNOWN_TOPIC", Instant.now().plusSeconds(60)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 목록은_내_만료_전_알림만_새것부터_주고_문구는_없다() throws Exception {
    UUID older =
        insertRaw(me, "SERVICE", "PHONE_VERIFICATION_RELEASED", Instant.now().plusSeconds(600));
    jdbc.update(
        "UPDATE state.in_app_notification SET created_at = created_at - INTERVAL '1 hour' WHERE id = ?",
        older);
    notifyReleased(me);
    insertRaw(me, "SERVICE", "PHONE_VERIFICATION_RELEASED", Instant.now().minusSeconds(1)); // 만료
    notifyReleased(other);

    mvc.perform(get("/api/me/notifications").with(loginAs(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[1].id").value(older.toString()))
        .andExpect(jsonPath("$[0].kind").value("SERVICE"))
        .andExpect(jsonPath("$[0].topic").value("PHONE_VERIFICATION_RELEASED"))
        .andExpect(jsonPath("$[0].roomRecordId").doesNotExist())
        .andExpect(jsonPath("$[0].target").doesNotExist())
        .andExpect(jsonPath("$[0].read").value(false))
        .andExpect(jsonPath("$[0].title").doesNotExist());
  }

  @Test
  void 읽으면_안_읽은_수가_줄고_다시_읽어도_처음_시각을_둔다() throws Exception {
    notifyReleased(me);
    notifyReleased(other); // 남의 알림은 세지 않는다
    UUID id = idOf(me);
    mvc.perform(get("/api/me/notifications/unread-count").with(loginAs(me)))
        .andExpect(jsonPath("$.count").value(1));

    mvc.perform(post("/api/me/notifications/{id}/read", id).with(loginAs(me)))
        .andExpect(status().isNoContent());
    Timestamp first =
        jdbc.queryForObject(
            "SELECT read_at FROM state.in_app_notification WHERE id = ?", Timestamp.class, id);
    mvc.perform(post("/api/me/notifications/{id}/read", id).with(loginAs(me)))
        .andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForObject(
                "SELECT read_at FROM state.in_app_notification WHERE id = ?", Timestamp.class, id))
        .isEqualTo(first);
    mvc.perform(get("/api/me/notifications/unread-count").with(loginAs(me)))
        .andExpect(jsonPath("$.count").value(0));
  }

  @Test
  void 남의_알림과_없는_알림은_읽지_못하고_404다() throws Exception {
    notifyReleased(other);
    UUID othersId = idOf(other);

    mvc.perform(post("/api/me/notifications/{id}/read", othersId).with(loginAs(me)))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/me/notifications/{id}/read", UUID.randomUUID()).with(loginAs(me)))
        .andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "SELECT read_at FROM state.in_app_notification WHERE id = ?",
                Timestamp.class,
                othersId))
        .isNull();
  }

  @Test
  void 로그인하지_않으면_401이다() throws Exception {
    mvc.perform(get("/api/me/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/me/notifications/unread-count")).andExpect(status().isUnauthorized());
  }

  private void notifyReleased(UUID memberId) {
    transaction.executeWithoutResult(status ->
        notifier.notify(InfoCommand.account(memberId, InAppTopic.PHONE_VERIFICATION_RELEASED)));
  }

  private UUID insertRaw(UUID memberId, String kind, String topic, Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.in_app_notification (id, member_id, kind, topic, expires_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        memberId,
        kind,
        topic,
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt.minusSeconds(3600 * 24 * 30)));
    return id;
  }

  private UUID idOf(UUID memberId) {
    return jdbc.queryForObject(
        "SELECT id FROM state.in_app_notification WHERE member_id = ?", UUID.class, memberId);
  }

  private long count(UUID memberId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.in_app_notification WHERE member_id = ?", Long.class, memberId);
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }
}
