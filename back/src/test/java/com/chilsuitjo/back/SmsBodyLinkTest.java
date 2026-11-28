package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.link.service.TestLinkTokens;
import com.chilsuitjo.back.notification.dto.Recipient;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeText;
import com.chilsuitjo.back.notification.service.FakeSmsOptOuts;
import com.chilsuitjo.back.notification.service.InMemoryRecipientDirectory;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.LinkAddresses;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 문자 본문의 초대 링크(#163 ③)를 실제 PostgreSQL과 실제 {@link LinkAddresses}로 확인한다. 업체는 부른 문자를 모으는 가짜로 바꾼다.
 * {@link SmsCancellationServiceTest}와 같은 설정이라 같은 컨텍스트(작업자 예약 실행 꺼짐)를 쓰고, 끝나면 남은 보낼 줄을 닫는다.
 */
@Import({TestcontainersConfiguration.class, OutboxWorkerTest.RecipientsConfig.class})
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SmsBodyLinkTest {

  /** test 설정의 {@code app.origin}. */
  private static final String ORIGIN = "http://localhost:5173";

  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private InMemoryRecipientDirectory recipients;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private LinkAddresses linkAddresses;
  @Autowired private DeliveryPollSchedule deliveryPollSchedule;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private NoticeRepository noticeRepository;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> rooms = new ArrayList<>();
  private final Map<UUID, String> sent = new ConcurrentHashMap<>();
  private UUID member;

  @BeforeEach
  void setUp() {
    recipients.reset();
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES ('9999999997', '테스트', '취소동')"
            + " ON CONFLICT DO NOTHING");
    member = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        member);
  }

  @AfterEach
  void closeLeftovers() {
    for (UUID r : rooms) {
      jdbc.update(
          """
          UPDATE outbox SET status = 'DONE'
          WHERE status IN ('PENDING', 'SENDING')
            AND dedupe_key IN (SELECT 'sms:' || id FROM notice WHERE room_record_id = ?)
          """,
          r);
    }
  }

  @Test
  void 초대_링크가_있으면_그_링크의_주소와_수신_거부_방법을_붙이고_토큰은_DB와_로그에_남기지_않는다(CapturedOutput output) {
    UUID room = room();
    UUID party = party(room);
    UUID link = validLink(room, party);
    recipients.seedRecipient(party, new Recipient.Invitee("01055556666"));
    UUID notice = enqueue(room, party, NoticeKind.INVITE, link);

    worker().runOnce();

    String token = TestLinkTokens.token(link);
    assertThat(sent.get(notice))
        .isEqualTo(
            NoticeText.of(NoticeKind.INVITE) + "\n" + ORIGIN + "/l#" + token + " (수신 거부는 링크에서)");
    assertThat(outboxStatus(notice)).isEqualTo("DONE");
    assertThat(output).doesNotContain(token);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE payload::text LIKE ? OR dedupe_key LIKE ?",
                Integer.class,
                "%" + token + "%",
                "%" + token + "%"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notice WHERE row_to_json(notice)::text LIKE ?",
                Integer.class,
                "%" + token + "%"))
        .isZero();
  }

  @Test
  void 한_번에_여러_통을_보내도_통지마다_자기_링크만_들어간다() {
    UUID roomA = room();
    UUID partyA = party(roomA);
    UUID linkA = validLink(roomA, partyA);
    UUID roomB = room();
    UUID partyB = party(roomB);
    UUID linkB = validLink(roomB, partyB);
    recipients.seedRecipient(partyA, new Recipient.Invitee("01055550001"));
    recipients.seedRecipient(partyB, new Recipient.Invitee("01055550002"));
    UUID noticeA = enqueue(roomA, partyA, NoticeKind.INVITE, linkA);
    UUID noticeB = enqueue(roomB, partyB, NoticeKind.INVITE, linkB);

    worker().runOnce();

    assertThat(sent.get(noticeA))
        .contains(TestLinkTokens.token(linkA))
        .doesNotContain(TestLinkTokens.token(linkB));
    assertThat(sent.get(noticeB))
        .contains(TestLinkTokens.token(linkB))
        .doesNotContain(TestLinkTokens.token(linkA));
  }

  @Test
  void 링크가_없는_통지에는_주소를_붙이지_않는다() {
    UUID room = room();
    UUID party = party(room);
    recipients.seedRecipient(party, new Recipient.Invitee("01055550003"));
    UUID notice = enqueue(room, party, NoticeKind.REQUEST, null);

    worker().runOnce();

    assertThat(sent.get(notice)).isEqualTo(NoticeText.of(NoticeKind.REQUEST));
  }

  @Test
  void 링크의_받는_자리가_통지와_다르면_보내지_않고_통지는_그대로_둔다(CapturedOutput output) {
    UUID room = room();
    UUID party = party(room);
    UUID other = party(room, "LANDLORD");
    UUID link = validLink(room, other);
    recipients.seedRecipient(party, new Recipient.Invitee("01055550004"));
    UUID notice = enqueue(room, party, NoticeKind.INVITE, link);

    worker().runOnce();

    assertThat(sent).doesNotContainKey(notice);
    assertThat(outboxStatus(notice)).isEqualTo("FAILED");
    assertThat(noticeStatus(notice)).isEqualTo("REQUESTED");
    assertThat(output)
        .contains("보낼 주소를 만들 수 없어 보내지 않음: notice=" + notice)
        .doesNotContain(TestLinkTokens.token(link));
  }

  @Test
  void 저장된_해시가_지금_키의_토큰과_다르면_열리지_않는_주소를_보내지_않는다() {
    UUID room = room();
    UUID party = party(room);
    UUID link = link(room, party, "other-key-hash");
    recipients.seedRecipient(party, new Recipient.Invitee("01055550005"));
    UUID notice = enqueue(room, party, NoticeKind.INVITE, link);

    worker().runOnce();

    assertThat(sent).doesNotContainKey(notice);
    assertThat(outboxStatus(notice)).isEqualTo("FAILED");
    assertThat(noticeStatus(notice)).isEqualTo("REQUESTED");
  }

  // ---- 도우미 ----

  private OutboxWorker worker() {
    SmsGateway gateway = mock(SmsGateway.class);
    when(gateway.send(any()))
        .thenAnswer(invocation -> {
          SmsMessage message = invocation.getArgument(0);
          sent.put(message.batchLeadId(), message.text());
          return SmsSendResult.sent("M-" + message.batchLeadId());
        });
    return new OutboxWorker(
        outboxRepository,
        noticeRepository,
        inviteLinks,
        linkAddresses,
        recipients,
        new FakeSmsOptOuts(),
        gateway,
        clock,
        transactionManager,
        50,
        List.of(Duration.ofMinutes(1)),
        Duration.ofDays(3),
        deliveryPollSchedule,
        "fake");
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999997', '링크 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    rooms.add(id);
    return id;
  }

  private UUID party(UUID roomId) {
    return party(roomId, "TENANT");
  }

  private UUID party(UUID roomId, String side) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, ?, 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        side);
    return id;
  }

  private UUID validLink(UUID roomId, UUID partyId) {
    UUID id = UUID.randomUUID();
    insertLink(id, roomId, partyId, TestLinkTokens.hash(id));
    return id;
  }

  private UUID link(UUID roomId, UUID partyId, String tokenHash) {
    UUID id = UUID.randomUUID();
    insertLink(id, roomId, partyId, tokenHash);
    return id;
  }

  private void insertLink(UUID id, UUID roomId, UUID partyId, String tokenHash) {
    jdbc.update(
        """
        INSERT INTO link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash,
          created_at, version)
        VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        partyId,
        member,
        tokenHash);
  }

  private UUID enqueue(UUID roomId, UUID partyId, NoticeKind kind, UUID linkId) {
    return transaction.execute(
        s -> noticeOutbox.enqueueSms(new SmsNoticeCommand(roomId, partyId, kind, null, linkId)));
  }

  private String outboxStatus(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox WHERE dedupe_key = ?", String.class, "sms:" + noticeId);
  }

  private String noticeStatus(UUID noticeId) {
    return jdbc.queryForObject("SELECT status FROM notice WHERE id = ?", String.class, noticeId);
  }
}
