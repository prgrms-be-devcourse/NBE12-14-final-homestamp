package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.dto.Recipient;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.repository.MockSolapi;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.repository.SolapiClient;
import com.chilsuitjo.back.notification.repository.SolapiSettings;
import com.chilsuitjo.back.notification.repository.SolapiSmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.service.DeliveryPollClaimer;
import com.chilsuitjo.back.notification.service.DeliveryResultPoller;
import com.chilsuitjo.back.notification.service.InMemoryRecipientDirectory;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.NoticeWaitStart;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import com.chilsuitjo.back.notification.service.SmsOptOuts;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 솔라피 발송기(NOTI-11)를 실제 아웃박스 작업자·전달 결과 작업자·기다림 시작에 끼워 가짜 솔라피 서버(HTTP)로 돌린다. 실제 업체는 부르지 않는다. 업체 구현이
 * 통지·요청 상태를 직접 바꾸지 않고, 기존 작업자가 기존 규칙대로 바꾸는지 본다.
 *
 * <p>앱 자체는 test 기본값(fake)으로 뜬다. 그래서 솔라피 부품이 앱에 없어 다른 테스트가 업체 주소로 요청을 보내지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
@ActiveProfiles("test")
class SolapiConnectionTest {

  /** 다른 테스트와 겹치지 않는 지역 코드. */
  private static final String REGION = "9999999989";

  private static final String PHONE = "01055556666";
  private static final String SEND = "/messages/v4/send-many/detail";
  private static final Duration RENOTIFY = Duration.ofHours(48);

  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private NoticeRepository notices;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private SmsOptOuts smsOptOuts;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TransactionTemplate transaction;
  @Autowired private DeliveryPollSchedule schedule;
  @Autowired private NoticeWaitStart waitStart;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;

  private final MockSolapi solapi = new MockSolapi();
  private final InMemoryRecipientDirectory recipients = new InMemoryRecipientDirectory();
  private SolapiSmsGateway gateway;
  private UUID room;
  private UUID party;

  @BeforeEach
  void setUp() {
    gateway =
        new SolapiSmsGateway(
            new SolapiClient(
                new SolapiSettings(
                    solapi.baseUrl(),
                    "test-key",
                    "test-secret",
                    "0212345678",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2)),
                clock));
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES (?, '테스트', '솔라피동') ON CONFLICT DO NOTHING",
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
    recipients.seedRecipient(party, new Recipient.Invitee(PHONE));
  }

  @AfterEach
  void cleanUp() {
    solapi.close();
    jdbc.update(
        "DELETE FROM outbox WHERE payload->>'noticeId' IN"
            + " (SELECT id::text FROM notice WHERE room_record_id = ?)",
        room);
    jdbc.update("DELETE FROM notice WHERE room_record_id = ?", room);
    jdbc.update("DELETE FROM response_request WHERE room_record_id = ?", room);
  }

  @Test
  void 앱의_기본_방식에서는_솔라피_부품이_없어_업체를_부르지_않는다() {
    assertThat(context.getBeanNamesForType(SolapiClient.class)).isEmpty();
    assertThat(context.getBeanNamesForType(SolapiSmsGateway.class)).isEmpty();
  }

  @Test
  void 접수되면_보낸_상태와_메시지_ID가_남고_업체의_4000이_오면_전달되어_기다림이_시작된다() {
    UUID request = waitingRequest();
    UUID notice = enqueue(request);
    solapi.reply(SEND, 200, accepted("M-ok", "G-ok", notice));

    worker().runOnce();

    assertThat(column(notice, "status")).isEqualTo("SENT");
    assertThat(column(notice, "provider_message_id")).isEqualTo("M-ok");
    assertThat(outboxStatus(notice)).isEqualTo("DONE");
    // 보내기만으로는 기다림이 시작되지 않는다(전달 증거가 아님, 03 불변식 4)
    assertThat(requestTime(request, "first_notified_at")).isNull();

    solapi.listed("M-ok", "4000", notice.toString());
    poller().runOnce(500);

    assertThat(column(notice, "status")).isEqualTo("DELIVERED");
    Instant delivered = time(notice, "delivered_at");
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(delivered);
    assertThat(requestTime(request, "renotify_due_at")).isEqualTo(delivered.plus(RENOTIFY));
  }

  @Test
  void 업체의_최종_실패면_통지는_실패이고_기다림은_시작되지_않는다() {
    UUID request = waitingRequest();
    UUID notice = enqueue(request);
    solapi.reply(SEND, 200, accepted("M-fail", "G-fail", notice));
    worker().runOnce();

    solapi.listed("M-fail", "3059", notice.toString());
    poller().runOnce(500);

    assertThat(column(notice, "status")).isEqualTo("FAILED");
    assertThat(requestTime(request, "first_notified_at")).isNull();
  }

  @Test
  void 아직_이통사_결과가_없으면_보낸_상태로_두고_기다림을_시작하지_않는다() {
    UUID request = waitingRequest();
    UUID notice = enqueue(request);
    solapi.reply(SEND, 200, accepted("M-wait", "G-wait", notice));
    worker().runOnce();

    solapi.listed("M-wait", "3000", notice.toString());
    poller().runOnce(500);

    assertThat(column(notice, "status")).isEqualTo("SENT");
    assertThat(requestTime(request, "first_notified_at")).isNull();
  }

  @Test
  void 업체_5xx로_받았는지_모르면_다시_보내지_않고_메시지_ID가_없어_조회도_전달도_없다() {
    UUID request = waitingRequest();
    UUID notice = enqueue(request);
    solapi.reply(SEND, 502, "bad gateway");

    worker().runOnce();
    worker().runOnce();

    assertThat(column(notice, "status")).isEqualTo("SENT");
    assertThat(column(notice, "provider_message_id")).isNull();
    assertThat(outboxStatus(notice)).isEqualTo("DONE");
    assertThat(sendsFor(notice)).isEqualTo(1);

    poller().runOnce(500);

    assertThat(column(notice, "status")).isEqualTo("SENT");
    assertThat(requestTime(request, "first_notified_at")).isNull();
  }

  @Test
  void 발신번호_미등록은_받는_사람의_실패로_적지_않고_다시_시도로_둔다() {
    UUID notice = enqueue(null);
    solapi.reply(
        SEND,
        200,
        "{\"failedMessageList\":[{\"statusCode\":\"1062\",\"statusMessage\":\"발신번호 미등록\"}]}");

    worker().runOnce();

    assertThat(column(notice, "status")).isEqualTo("REQUESTED");
    assertThat(outboxStatus(notice)).isEqualTo("PENDING");
  }

  @Test
  void 수신_거부한_번호면_업체를_부르지_않는다() {
    // 수신 거부는 지우지 않는 기록이라 이 테스트만 쓰는 번호로 한다
    String optedOut =
        "010" + (10_000_000 + Math.floorMod(UUID.randomUUID().hashCode(), 89_999_999));
    recipients.seedRecipient(party, new Recipient.Invitee(optedOut));
    transaction.executeWithoutResult(status -> smsOptOuts.optOut(optedOut, clock.instant()));
    UUID notice = enqueue(null);

    worker().runOnce();

    assertThat(sendsFor(notice)).isZero();
    assertThat(column(notice, "status")).isEqualTo("REQUESTED");
  }

  // ---- 도우미 ----

  private OutboxWorker worker() {
    return new OutboxWorker(
        outboxRepository,
        notices,
        inviteLinks,
        recipients,
        smsOptOuts,
        gateway,
        clock,
        transactionManager,
        50,
        List.of(Duration.ofMinutes(1)),
        Duration.ofDays(3),
        schedule,
        "solapi");
  }

  /** 보낸 뒤 첫 조회 시각(1분)이 지난 때에 가져간다. */
  private DeliveryResultPoller poller() {
    Instant later = clock.instant().plus(Duration.ofMinutes(10));
    return new DeliveryResultPoller(
        new DeliveryPollClaimer(
            notices, Clock.fixed(later, ZoneOffset.UTC), transactionManager, schedule),
        notices,
        gateway,
        Clock.fixed(later.plusSeconds(1), ZoneOffset.UTC),
        transactionManager,
        Duration.ofMinutes(2),
        waitStart);
  }

  private UUID enqueue(UUID request) {
    return transaction.execute(status ->
        noticeOutbox.enqueueSms(
            new SmsNoticeCommand(room, party, NoticeKind.REQUEST, request, null)));
  }

  private UUID waitingRequest() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO response_request (id, room_record_id, kind, requested_by_side, status,
          created_at, version)
        VALUES (?, ?, 'CORRECTION_CONFIRM', 'TENANT', 'WAITING', ?, 0)
        """,
        id,
        room,
        Timestamp.from(clock.instant().minusSeconds(60)));
    return id;
  }

  private static String accepted(String messageId, String groupId, UUID batch) {
    return """
    {"groupInfo":{"groupId":"%s","count":{"total":1,"registeredFailed":0}},"failedMessageList":[],
     "messageList":[{"messageId":"%s","statusCode":"2000","customFields":{"batchLeadId":"%s"}}]}
    """
        .formatted(groupId, messageId, batch);
  }

  /** 이 통지의 묶음 ID를 담아 업체에 보낸 요청 수. */
  private long sendsFor(UUID notice) {
    return solapi.received().stream()
        .filter(r -> r.path().equals(SEND) && r.body().contains(notice.toString()))
        .count();
  }

  private String column(UUID id, String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM notice WHERE id = ?", String.class, id);
  }

  private Instant time(UUID id, String column) {
    Timestamp value =
        jdbc.queryForObject("SELECT " + column + " FROM notice WHERE id = ?", Timestamp.class, id);
    return value == null ? null : value.toInstant();
  }

  private Instant requestTime(UUID id, String column) {
    Timestamp value =
        jdbc.queryForObject(
            "SELECT " + column + " FROM response_request WHERE id = ?", Timestamp.class, id);
    return value == null ? null : value.toInstant();
  }

  private String outboxStatus(UUID notice) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox WHERE dedupe_key = ?", String.class, "sms:" + notice);
  }
}
