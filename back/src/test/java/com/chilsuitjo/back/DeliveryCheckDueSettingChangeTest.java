package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.dto.Recipient;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.service.DeliveryPollClaimer;
import com.chilsuitjo.back.notification.service.DeliveryResultPoller;
import com.chilsuitjo.back.notification.service.InMemoryRecipientDirectory;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.NoticeWaitStart;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전달 확인 기한은 보낼 때 정해 저장하고, 설정({@code app.delivery.unknown-after})을 바꿔도 이미 보낸 통지의 기한은 그대로다(06 0-1, 03
 * 1-1 "시간 값", PR #124 리뷰). 설정이 다른 보내기 작업자로 실제 아웃박스를 거쳐 보내 DB에 저장하고, 다시 읽은 통지를 전달 결과 작업자가 처리한다.
 *
 * <p>작업자를 여러 번 커밋하므로 테스트 트랜잭션으로 감싸지 않는다. 다른 테스트의 줄과 섞이지 않게 2019년 고정 시계와 전용 지역 코드를 쓴다(아웃박스·조회 작업자는 그
 * 시각까지 된 줄만 가져감).
 *
 * <p>번호 조회는 {@link OutboxWorkerTest}와 같은 test 가짜({@link InMemoryRecipientDirectory})를 받는다(main 가짜는
 * 지움, #268).
 */
@Import({TestcontainersConfiguration.class, OutboxWorkerTest.RecipientsConfig.class})
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
@ActiveProfiles("test")
class DeliveryCheckDueSettingChangeTest {

  private static final String REGION = "9999999995";
  private static final Instant T0 = Instant.parse("2019-01-01T00:00:00Z");
  private static final Instant T1 = T0.plus(Duration.ofHours(1));
  private static final Duration THREE_DAYS = Duration.ofDays(3);
  private static final DeliveryPollSchedule SCHEDULE =
      new DeliveryPollSchedule(
          List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));

  @Autowired private NoticeRepository notices;
  @Autowired private OutboxRepository outbox;
  @Autowired private FakeSmsGateway gateway;
  @Autowired private InMemoryRecipientDirectory recipients;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private NoticeWaitStart waitStart;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;

  private UUID room;
  private UUID party;

  @BeforeEach
  void setUp() {
    gateway.reset();
    recipients.reset();
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES (?, '테스트', '기한동') ON CONFLICT DO NOTHING",
        REGION);
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, ?, '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room,
        REGION);
    party = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        party,
        room);
    recipients.seedRecipient(party, new Recipient.Invitee("01012345678"));
  }

  @AfterEach
  void cleanUp() {
    gateway.reset();
    jdbc.update(
        "DELETE FROM notice WHERE room_record_id IN (SELECT id FROM room_record WHERE region_code = ?)",
        REGION);
  }

  @Test
  void 기한_설정을_줄여도_이미_보낸_통지는_일찍_실패하지_않고_새_통지만_새_기한을_따른다() {
    UUID before = sendAt(T0, THREE_DAYS);
    UUID after = sendAt(T1, Duration.ofMinutes(5));

    // 다시 읽어도 보낼 때 정한 기한이다
    assertThat(dueAt(before)).isEqualTo(T0.plus(THREE_DAYS));
    assertThat(dueAt(after)).isEqualTo(T1.plus(Duration.ofMinutes(5)));

    // 설정을 줄인 뒤 결과 없음: 새 통지는 자기 기한(5분)이 지나 실패, 앞서 보낸 통지는 3일 기한 전이라 그대로
    pollAt(T1.plus(Duration.ofMinutes(10)));
    assertThat(status(after)).isEqualTo("FAILED");
    assertThat(status(before)).isEqualTo("SENT");
    assertThat(dueAt(before)).isEqualTo(T0.plus(THREE_DAYS));

    // 조회 자체가 실패해도 기한 전이면 그대로이고 기한도 바뀌지 않는다
    gateway.failQueries(true);
    pollAt(T1.plus(Duration.ofMinutes(30)));
    gateway.failQueries(false);
    assertThat(status(before)).isEqualTo("SENT");
    assertThat(dueAt(before)).isEqualTo(T0.plus(THREE_DAYS));

    // 저장된 기한 직전에는 그대로, 기한이 되면 전달 확인 기한 만료로 실패
    pollAt(T0.plus(THREE_DAYS).minusSeconds(1));
    assertThat(status(before)).isEqualTo("SENT");
    pollAt(T0.plus(THREE_DAYS));
    assertThat(status(before)).isEqualTo("FAILED");
    assertThat(dueAt(before)).isEqualTo(T0.plus(THREE_DAYS));
  }

  @Test
  void 기한_설정을_늘려도_이미_보낸_통지의_기한은_늘어나지_않는다() {
    UUID before = sendAt(T0, THREE_DAYS);
    UUID after = sendAt(T1, Duration.ofDays(7));

    assertThat(dueAt(after)).isEqualTo(T1.plus(Duration.ofDays(7)));

    pollAt(T0.plus(THREE_DAYS));

    assertThat(status(before)).isEqualTo("FAILED");
    assertThat(dueAt(before)).isEqualTo(T0.plus(THREE_DAYS));
    assertThat(status(after)).isEqualTo("SENT");
    assertThat(dueAt(after)).isEqualTo(T1.plus(Duration.ofDays(7)));
  }

  /** {@code at}에 넣고, 그 시각에 기한 설정이 {@code giveUpAfter}인 보내기 작업자가 보낸다. */
  private UUID sendAt(Instant at, Duration giveUpAfter) {
    Clock clock = Clock.fixed(at, ZoneOffset.UTC);
    UUID id =
        transaction.execute(status ->
            new NoticeOutbox(notices, outbox, clock)
                .enqueueSms(new SmsNoticeCommand(room, party, NoticeKind.REQUEST, null, null)));
    new OutboxWorker(
            outbox,
            notices,
            inviteLinks,
            recipients,
            gateway,
            clock,
            transactionManager,
            50,
            List.of(Duration.ofMinutes(1)),
            giveUpAfter,
            SCHEDULE,
            "fake")
        .runOnce();
    assertThat(status(id)).isEqualTo("SENT");
    return id;
  }

  /** {@code at}에 전달 결과 작업자가 돈다. 이 작업자는 기한 설정을 읽지 않는다. */
  private void pollAt(Instant at) {
    Clock clock = Clock.fixed(at, ZoneOffset.UTC);
    new DeliveryResultPoller(
            new DeliveryPollClaimer(notices, clock, transactionManager, SCHEDULE),
            notices,
            gateway,
            clock,
            transactionManager,
            Duration.ofMinutes(2),
            waitStart)
        .runOnce(50);
  }

  private String status(UUID id) {
    return jdbc.queryForObject("SELECT status FROM notice WHERE id = ?", String.class, id);
  }

  private Instant dueAt(UUID id) {
    return jdbc.queryForObject(
            "SELECT delivery_check_due_at FROM notice WHERE id = ?", Timestamp.class, id)
        .toInstant();
  }
}
