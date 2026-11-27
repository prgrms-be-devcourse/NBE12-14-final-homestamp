package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.Recipient;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.Outbox;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.rule.NoticeText;
import com.chilsuitjo.back.notification.rule.OutboxStatus;
import com.chilsuitjo.back.notification.rule.SmsMode;
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
 * <p>업체에 닿지 못하면 {@code notice.send.retry}(1분·5분, 그 뒤 15분마다) 간격으로 다시 시도하고, 넣은 지 {@code
 * delivery.unknown-after}(3일)가 지나면 최종 실패로 둔다(06 0-1). 최종 실패는 "다시 보내기 → 또 실패면 혼자 진행"으로 이어지므로(03
 * 1-1), 업체 장애를 받는 사람 탓으로 돌리지 않으려고 짧게 포기하지 않는다.
 *
 * <p>업체 호출 중에 앱이 멈추면 줄이 {@code SENDING}으로 남고, 켜질 때 다시 보내지 않고 전달 결과 조회로 맞춘다(OPS-1, 2주차). 그래서 "자동으로 두
 * 번 보내지 않는다".
 */
@Service
public class OutboxWorker {

  private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

  private final OutboxRepository outbox;
  private final NoticeRepository notices;
  private final InviteLinks inviteLinks;
  private final RecipientDirectory recipients;
  private final SmsOptOuts smsOptOuts;
  private final SmsGateway gateway;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final int batchSize;
  private final List<Duration> retryDelays;
  private final Duration giveUpAfter;
  private final boolean sendingDisabled;
  private final DeliveryPollSchedule deliveryPollSchedule;

  public OutboxWorker(
      OutboxRepository outbox,
      NoticeRepository notices,
      InviteLinks inviteLinks,
      RecipientDirectory recipients,
      SmsOptOuts smsOptOuts,
      SmsGateway gateway,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.outbox.worker.batch-size:50}") int batchSize,
      @Value("${app.notice.send.retry:1m,5m,15m}") List<Duration> retryDelays,
      @Value("${app.delivery.unknown-after:3d}") Duration giveUpAfter,
      DeliveryPollSchedule deliveryPollSchedule,
      @Value("${" + SmsMode.PROPERTY + ":}") String smsMode) {
    if (retryDelays.isEmpty()) {
      throw new IllegalArgumentException("app.notice.send.retry가 비어 있어요");
    }
    // 기한은 문자를 보낸 뒤 결과를 적을 때 계산한다. 그때 틀린 설정이 드러나면 이미 보낸 문자 줄이 SENDING에 남으므로 만들 때 막는다.
    // DB(timestamptz)는 마이크로초까지라, 그보다 작은 단위가 있으면 보낸 시각과 기한이 같은 값으로 저장될 수 있다
    if (giveUpAfter == null
        || giveUpAfter.compareTo(Duration.ofNanos(1_000)) < 0
        || giveUpAfter.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException("app.delivery.unknown-after는 1마이크로초 이상이고 마이크로초 단위여야 해요");
    }
    this.outbox = outbox;
    this.notices = notices;
    this.inviteLinks = inviteLinks;
    this.recipients = recipients;
    this.smsOptOuts = smsOptOuts;
    this.gateway = gateway;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
    this.batchSize = batchSize;
    this.retryDelays = List.copyOf(retryDelays);
    this.giveUpAfter = giveUpAfter;
    this.deliveryPollSchedule = deliveryPollSchedule;
    // 값 검사·조합 거절은 SmsModeGuard가 기동 전에 한다. 여기서는 모르는 값을 조용히 다른 방식으로 읽지 않게만 막는다
    this.sendingDisabled =
        SmsMode.parse(smsMode)
                .orElseThrow(() -> new IllegalStateException("문자 방식(app.sms.mode)을 읽을 수 없어요"))
            == SmsMode.DISABLED;
  }

  /** 한 번 돌고 처리한 줄 수를 돌려준다. 한 줄에서 예외가 나도 나머지 줄은 처리한다. */
  public int runOnce() {
    int processed = 0;
    while (processed < batchSize) {
      UUID outboxId = claim();
      if (outboxId == null) {
        break;
      }
      processed++;
      try {
        process(outboxId);
      } catch (RuntimeException e) {
        log.error("아웃박스 줄 처리 실패: outbox={}, error={}", outboxId, e.getClass().getName());
      }
    }
    return processed;
  }

  private UUID claim() {
    return transaction.execute(status -> {
      List<Outbox> rows = outbox.lockDueSms(clock.instant(), 1);
      if (rows.isEmpty()) {
        return null;
      }
      Outbox row = rows.getFirst();
      row.markSending();
      return row.getId();
    });
  }

  /**
   * 업체를 부르기 전 단계(통지·번호 읽기)에서 예외가 나면 업체에 닿지 않은 게 확실하므로 다시 시도하게 둔다. 부른 뒤 결과를 적다가 실패하면 줄이 {@code
   * SENDING}으로 남고 전달 결과 조회로 맞춘다(다시 보내지 않음).
   */
  private void process(UUID outboxId) {
    Optional<Notice> notice;
    Optional<String> phone;
    boolean optedOut;
    try {
      Outbox row = outbox.findById(outboxId).orElse(null);
      if (row == null) {
        return;
      }
      Optional<UUID> savedNoticeId = row.noticeId();
      if (savedNoticeId.isEmpty() || !("sms:" + savedNoticeId.get()).equals(row.getDedupeKey())) {
        transaction.executeWithoutResult(
            status -> outbox.findById(outboxId).ifPresent(Outbox::markFailed));
        log.error("문자 작업의 payload 또는 중복 방지 키가 올바르지 않음: outbox={}", outboxId);
        return;
      }
      notice = notices.findById(savedNoticeId.get());
      if (notice.isEmpty()) {
        // 호실이 지워져 통지도 함께 지워졌다(통지는 호실 CASCADE, 아웃박스는 FK 없음). 보낼 것이 없다.
        transaction.executeWithoutResult(
            status -> outbox.findById(outboxId).ifPresent(Outbox::markDone));
        return;
      }
      if (notice.get().getLinkId() != null
          && inviteLinks.isInvalidatedInvite(notice.get().getLinkId())) {
        cancelInvalidatedInvite(outboxId, notice.get().getId());
        return;
      }
      if (sendingDisabled) {
        // disabled는 받는 사람을 읽지 않고 보내지 않은 채 끝낸다(decisions 2026-11-25·26 운영 문자 모드). 꺼진 동안의 문자가 켤 때
        // 한꺼번에
        // 나가지 않게 다시 시도하지 않고, 업체 실패가 아니라서 통지를 최종 실패로 두지도 않는다. 받는 사람 상태를 꾸며내지 않으려고 resolve를 부르지 않는다
        skipWhileDisabled(outboxId, notice.get().getId());
        return;
      }
      phone = phoneOf(recipients.resolve(notice.get().getRecipientPartyId()));
      // 수신 거부는 서비스 전체에서 그 번호로 보내지 않는다(03 K "수신 중단"). 넣을 때가 아니라 업체를 부르기 직전에 다시 본다: 넣은 뒤 거부한 번호에
      // 가지 않게(decisions 2026-11-27 수신 거부 조회 "발송 직전 재확인"). 조회가 실패하면 예외라 아래에서 보내지 않고 다시 시도로 둔다(false로
      // 바꾸지 않음)
      optedOut = phone.isPresent() && smsOptOuts.isOptedOut(phone.get());
    } catch (RuntimeException e) {
      log.warn("보내기 전 단계 실패, 다시 시도: outbox={}, error={}", outboxId, e.getClass().getName());
      finish(outboxId, null, SmsSendResult.retry());
      return;
    }
    if (phone.isEmpty()) {
      skipWithoutPhone(outboxId, notice.get().getId());
      return;
    }
    if (optedOut) {
      skipOptedOut(outboxId, notice.get().getId());
      return;
    }
    SmsSendResult result = send(notice.get(), phone.get());
    finish(outboxId, notice.get().getId(), result);
  }

  /**
   * 가져간 줄(첫 시도든 {@code RETRY} 뒤 다시 시도든)의 초대 링크가 업체에 보내기 전에 이미 무효면 이 시도는 보내지 않고 통지와 줄을 함께 취소로 끝낸다.
   * 줄이 이 작업자가 가져간 {@code SENDING}이고 통지가 발송 요청됨일 때만 바꾼다.
   */
  private void cancelInvalidatedInvite(UUID outboxId, UUID noticeId) {
    transaction.executeWithoutResult(status -> {
      Notice notice = notices.findForUpdate(noticeId).orElse(null);
      Outbox row = outbox.findById(outboxId).orElse(null);
      if (notice != null
          && row != null
          && row.getStatus() == OutboxStatus.SENDING
          && notice.markCancelled()) {
        row.markCancelledBeforeSend();
      }
    });
  }

  /**
   * 보낼 번호가 없다. 수신 거부·탈퇴·합류처럼 요청을 닫는 일이 넣은 뒤에 일어났거나, 문자 대신 서비스 안 알림으로 갔어야 하는 경우다. 업체 실패가 아니므로 통지를 최종
   * 실패로 두지 않는다("문자가 가지 않았어요"와 [다시 보내기]가 뜨면 이유가 틀리다). 줄만 보내지 않은 채 끝내고 운영이 볼 수 있게 남긴다.
   *
   * <p><b>남는 빈틈(NOTI-1에서 닫음)</b>: 연결된 회원의 번호 인증이 풀린 경우(다른 계정이 같은 번호를 인증, 불변식 8)는 요청이 열린 채 남아 통지가
   * {@code REQUESTED}에 멈추고 기다림이 시작되지 않는다(원칙 4). 05 {@code notice}대로 넣을 때 채널을 고르고(문자를 끈 회원·인증이 풀린
   * 계정은 {@code IN_APP}), 보낼 때 번호가 사라진 회원은 {@code IN_APP}으로 바꾼다. 기다림 타이머가 돌기 전에 넣는다(#93).
   *
   * <p>번호를 고쳐 다시 초대할 때 옛 링크의 보내지 않은 문자를 막는 것은 이 작업자가 아니다. room이 {@code save} 안에서 넘긴 링크 ID의 줄을
   * {@link SmsCancellation}으로 취소하고(03 K "보내기 전 취소", room 호출은 아직 없음), 작업자는 {@code PENDING}만 가져가서 취소된
   * 줄을 보내지 않는다. 이미 가져간({@code SENDING}) 줄도 발송 직전 링크가 무효인 것을 확인하면 취소한다. 확인을 끝낸 뒤 업체 호출에 들어가는 경합은 막지
   * 못한다(#100).
   */
  private void skipWithoutPhone(UUID outboxId, UUID noticeId) {
    transaction.executeWithoutResult(
        status -> outbox.findById(outboxId).ifPresent(Outbox::markFailed));
    log.warn("보낼 번호가 없어 문자를 보내지 않음: notice={}", noticeId);
  }

  /**
   * 받는 번호가 수신 거부 목록에 있다. 번호가 없을 때와 같은 끝이다: 줄만 보내지 않은 채 끝내고, 업체 실패가 아니므로 통지를 최종 실패로 두지 않는다("문자가 가지
   * 않았어요"·[다시 보내기]가 뜨면 이유가 틀림). 문자 대신 서비스 안 알림으로 바꾸기(05 {@code notice} 채널)와 그때 요청을 혼자 진행 가능(상대 수신
   * 거부)으로 둘지는 NOTI-1·#163에서 정한다. 로그에는 번호를 남기지 않는다.
   */
  private void skipOptedOut(UUID outboxId, UUID noticeId) {
    transaction.executeWithoutResult(
        status -> outbox.findById(outboxId).ifPresent(Outbox::markFailed));
    log.warn("받는 번호가 수신 거부라 문자를 보내지 않음: notice={}", noticeId);
  }

  /**
   * 번호가 있는 두 결과만 보낸다. 인증 풀림·번호 지움·받을 사람 없음은 빈 값이다(#242). 판정할 수 없으면 {@code resolve}가 예외를 올려 다시 시도로
   * 둔다(빈 값으로 바꾸지 않음).
   */
  private static Optional<String> phoneOf(Recipient recipient) {
    return switch (recipient) {
      case Recipient.VerifiedMember member -> Optional.of(member.phone());
      case Recipient.Invitee invitee -> Optional.of(invitee.phone());
      case Recipient.UnverifiedMember unverified -> Optional.empty();
      case Recipient.InviteeWithoutPhone withoutPhone -> Optional.empty();
      case Recipient.Unavailable unavailable -> Optional.empty();
    };
  }

  /** disabled에서 보내지 않고 끝낸다. 번호가 없을 때와 같은 끝(줄만 끝내고 통지는 그대로)이고, 로그로 이유를 구분한다. */
  private void skipWhileDisabled(UUID outboxId, UUID noticeId) {
    transaction.executeWithoutResult(
        status -> outbox.findById(outboxId).ifPresent(Outbox::markFailed));
    log.warn("문자 발송이 꺼져 있어(app.sms.mode=disabled) 보내지 않음: notice={}", noticeId);
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

  private void finish(UUID outboxId, UUID noticeId, SmsSendResult result) {
    transaction.executeWithoutResult(status -> {
      Instant now = clock.instant();
      Outbox row = outbox.findById(outboxId).orElseThrow();
      Notice notice = noticeId == null ? null : notices.findForUpdate(noticeId).orElse(null);
      switch (result.outcome()) {
        case SENT, UNKNOWN -> {
          row.markDone();
          if (notice != null) {
            // 전달 확인 기한은 보낸 이 순간의 설정으로 한 번만 정해 저장한다(06 0-1). 뒤에 설정을 바꿔도 이 통지의 기한은 그대로다
            Instant deliveryCheckDueAt = DeliveryPollSchedule.deadline(now, giveUpAfter);
            notice.markSent(
                result.providerMessageId(),
                now,
                deliveryPollSchedule.first(now, deliveryCheckDueAt),
                deliveryCheckDueAt);
          }
        }
        case REJECTED -> {
          row.markDone();
          if (notice != null) {
            notice.markFailed(now);
          }
        }
        case RETRY -> {
          Instant next = now.plus(retryDelay(row.getAttempts()));
          if (next.isAfter(row.getCreatedAt().plus(giveUpAfter))) {
            if (noticeId == null) {
              UUID savedNoticeId =
                  row.noticeId().orElseThrow(() -> new IllegalStateException("문자 작업에 통지 ID가 없어요"));
              notice = notices.findForUpdate(savedNoticeId).orElse(null);
              if (notice == null) {
                // 호실 삭제로 통지가 사라졌으면 보낼 대상도 없다.
                row.markDone();
                return;
              }
            }
            row.markFailed();
            if (notice != null) {
              notice.markFailed(now);
            }
          } else {
            row.retryAt(next);
          }
        }
      }
    });
  }

  /** {@code attempts}번째 시도 뒤의 간격. 목록을 다 쓰면 마지막 간격을 계속 쓴다. */
  private Duration retryDelay(int attempts) {
    return retryDelays.get(Math.min(Math.max(attempts, 1), retryDelays.size()) - 1);
  }
}
