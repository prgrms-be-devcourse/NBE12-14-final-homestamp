package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.Outbox;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.NoticeText;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아웃박스 문자 작업자(04 4-2). 한 번 돌 때:
 *
 * <ol>
 *   <li>보낼 시각이 지난 줄을 {@code SKIP LOCKED}로 잠가 {@code SENDING}으로 바꾸고 커밋한다
 *   <li>트랜잭션 밖에서 번호를 읽고 업체를 부른다(번호는 이 안에서만 쓰고 저장·로그하지 않음)
 *   <li>결과를 줄마다 새 트랜잭션으로 적는다
 * </ol>
 *
 * <p>업체 호출 중에 앱이 멈추면 줄이 {@code SENDING}으로 남고, 켜질 때 다시 보내지 않고 전달 결과 조회로 맞춘다(OPS-1, 2주차). 그래서 "자동으로 두
 * 번 보내지 않는다".
 */
@Service
public class OutboxWorker {

  private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

  /** 업체에 닿지 않았을 때 다시 시도하는 간격. 다 쓰면 실패로 둔다. */
  static final List<Duration> RETRY_DELAYS =
      List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

  private final OutboxRepository outbox;
  private final NoticeRepository notices;
  private final RecipientDirectory recipients;
  private final SmsGateway gateway;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final int batchSize;

  public OutboxWorker(
      OutboxRepository outbox,
      NoticeRepository notices,
      RecipientDirectory recipients,
      SmsGateway gateway,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.outbox.worker.batch-size:50}") int batchSize) {
    this.outbox = outbox;
    this.notices = notices;
    this.recipients = recipients;
    this.gateway = gateway;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
    this.batchSize = batchSize;
  }

  /** 한 번 돌고 처리한 줄 수를 돌려준다. */
  public int runOnce() {
    List<UUID> claimed = claim();
    for (UUID outboxId : claimed) {
      process(outboxId);
    }
    return claimed.size();
  }

  private List<UUID> claim() {
    return transaction.execute(status -> {
      List<Outbox> rows = outbox.lockDueSms(clock.instant(), batchSize);
      rows.forEach(Outbox::markSending);
      return rows.stream().map(Outbox::getId).toList();
    });
  }

  private void process(UUID outboxId) {
    Outbox row = outbox.findById(outboxId).orElse(null);
    if (row == null) {
      return;
    }
    Optional<Notice> notice = row.noticeId().flatMap(notices::findById);
    if (notice.isEmpty()) {
      // 호실이 지워져 통지도 함께 지워졌다(통지는 호실 CASCADE, 아웃박스는 FK 없음). 보낼 것이 없다.
      finish(outboxId, null, Outcome.DROP);
      return;
    }
    Optional<String> phone = recipients.phoneOfRecipient(notice.get().getRecipientPartyId());
    if (phone.isEmpty()) {
      finish(outboxId, notice.get().getId(), Outcome.NO_PHONE);
      return;
    }
    SmsSendResult result = send(notice.get(), phone.get());
    finish(outboxId, notice.get().getId(), result);
  }

  private SmsSendResult send(Notice notice, String phone) {
    try {
      return gateway.send(new SmsMessage(notice.getId(), phone, NoticeText.of(notice.getKind())));
    } catch (RuntimeException e) {
      // 업체에 닿았는지 모른다. 보낸 것으로 보고 다시 보내지 않는다. 예외 문구에 번호가 있을 수 있어 종류만 남긴다.
      log.warn(
          "문자 발송 중 예외, 결과 모름으로 둠: notice={}, error={}", notice.getId(), e.getClass().getName());
      return SmsSendResult.unknown();
    }
  }

  private enum Outcome {
    DROP,
    NO_PHONE
  }

  private void finish(UUID outboxId, UUID noticeId, Outcome outcome) {
    transaction.executeWithoutResult(status -> {
      Instant now = clock.instant();
      Outbox row = outbox.findById(outboxId).orElseThrow();
      row.markDone();
      if (outcome == Outcome.NO_PHONE) {
        notices.findById(noticeId).ifPresent(n -> n.markFailed(now));
      }
    });
  }

  private void finish(UUID outboxId, UUID noticeId, SmsSendResult result) {
    transaction.executeWithoutResult(status -> {
      Instant now = clock.instant();
      Outbox row = outbox.findById(outboxId).orElseThrow();
      Notice notice = notices.findById(noticeId).orElse(null);
      switch (result.outcome()) {
        case SENT, UNKNOWN -> {
          row.markDone();
          if (notice != null) {
            notice.markSent(result.providerMessageId(), now);
          }
        }
        case REJECTED -> {
          row.markDone();
          if (notice != null) {
            notice.markFailed(now);
          }
        }
        case RETRY -> {
          int used = row.getAttempts();
          if (used <= RETRY_DELAYS.size()) {
            row.retryAt(now.plus(RETRY_DELAYS.get(used - 1)));
          } else {
            row.markFailed();
            if (notice != null) {
              notice.markFailed(now);
            }
          }
        }
      }
    });
  }
}
