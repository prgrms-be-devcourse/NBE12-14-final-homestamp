package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.ClaimedDeliveryPoll;
import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보낸 문자의 전달 결과를 물어 통지에 적는 작업자(04 4-2, #120 3-B1). 한 번 돌 때:
 *
 * <ol>
 *   <li>{@link DeliveryPollClaimer}가 시각이 된 통지를 가져가고 다음 조회 시각을 옮겨 커밋한다
 *   <li>트랜잭션 밖에서 업체에 한꺼번에 묻는다
 *   <li>통지마다 새 트랜잭션에서 다시 잠그고, 아직 보낸(SENT) 상태일 때만 결과를 적는다
 * </ol>
 *
 * <p>전달됨이면 전달됨, 업체의 최종 실패면 실패다. 결과가 없거나 업체 조회가 실패하면 기한(보낸 뒤 {@code delivery.unknown-after}, 3일) 전에는
 * 그대로 두고 옮겨 둔 시각에 다시 묻는다. 기한이 지났으면 실패로 끝낸다: 전달됐다는 증거가 아니라 "기한 안에 전달을 확인하지 못함"이라는 뜻이다(04 4-2 "3일이
 * 지나도 결과가 없으면 실패로 본다"). 조회 실패로 문자를 다시 보내지는 않는다.
 *
 * <p>통지 하나가 묶음 하나인 지금 구조를 기준으로 한다(문자 모으기의 묶음 전체 변경은 아직 없음). 결과는 가져간 통지의 묶음 ID로만 찾으므로, 업체가 모르는 ID를
 * 돌려주거나 일부를 빠뜨려도 다른 통지에 적히지 않는다(빠진 통지는 결과 없음).
 *
 * <p>일정 간격 실행은 {@link DeliveryPollScheduler}가 한다. 이 작업자는 문자를 보내지 않는다(업체에 결과만 묻는다).
 */
@Service
public class DeliveryResultPoller {

  private static final Logger log = LoggerFactory.getLogger(DeliveryResultPoller.class);

  private final DeliveryPollClaimer claimer;
  private final NoticeRepository notices;
  private final SmsGateway gateway;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final Duration giveUpAfter;

  public DeliveryResultPoller(
      DeliveryPollClaimer claimer,
      NoticeRepository notices,
      SmsGateway gateway,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.delivery.unknown-after:3d}") Duration giveUpAfter,
      @Value("${app.delivery.poll:1m,5m,15m}") List<Duration> pollSchedule) {
    DeliveryPollSchedule.requireSameAs(pollSchedule);
    this.claimer = claimer;
    this.notices = notices;
    this.gateway = gateway;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.giveUpAfter = giveUpAfter;
  }

  /** 많아야 {@code limit}개를 가져가 묻고, 가져간 통지 수를 돌려준다. 한 통지에서 예외가 나도 나머지는 적는다. */
  public int runOnce(int limit) {
    List<ClaimedDeliveryPoll> claimed = claimer.claim(limit);
    if (claimed.isEmpty()) {
      return 0;
    }
    Map<UUID, DeliveryOutcome> results = query(claimed);
    for (ClaimedDeliveryPoll poll : claimed) {
      try {
        // 업체가 이 묶음 ID를 빠뜨렸으면 결과 없음과 같다. 조회 자체가 실패했으면(results == null) 이번에는 아무 결과도 모른다
        DeliveryOutcome outcome = results == null ? null : results.get(poll.query().batchLeadId());
        record(poll, outcome);
      } catch (RuntimeException e) {
        log.error("전달 결과 저장 실패: notice={}, error={}", poll.noticeId(), e.getClass().getName());
      }
    }
    return claimed.size();
  }

  /** 업체에 묻는다. 조회 자체가 실패하면 null(결과 없음과 다름). */
  private Map<UUID, DeliveryOutcome> query(List<ClaimedDeliveryPoll> claimed) {
    List<DeliveryQuery> queries = claimed.stream().map(ClaimedDeliveryPoll::query).toList();
    try {
      return gateway.queryResults(queries);
    } catch (RuntimeException e) {
      log.warn("전달 결과 조회 실패: count={}, error={}", queries.size(), e.getClass().getName());
      return null;
    }
  }

  /**
   * 통지를 다시 잠그고 결과를 적는다. 기한은 가져간 시각({@code pastDeadline})만 믿지 않고 적는 지금 다시 본다(업체에 묻는 사이 기한이 지날 수 있음).
   *
   * <p>업체에 묻는 사이 다음 조회 시각이 지나 다른 작업자가 다시 가져갔으면({@code next_poll_at}이 가져갈 때 적은 값과 다름) 전달됨만 적고 나머지는
   * 버린다. 다만 최신 작업자가 먼저 기한 만료 실패를 적었으면 뒤늦은 전달됨도 반영되지 않는다(FAILED는 되돌리지 않음, 후속 결정).
   *
   * @param outcome 업체 결과. null이면 조회 실패로 결과를 모름
   */
  private void record(ClaimedDeliveryPoll poll, DeliveryOutcome outcome) {
    transaction.executeWithoutResult(status -> {
      Notice notice = notices.findForUpdate(poll.noticeId()).orElse(null);
      if (notice == null || notice.getStatus() != NoticeStatus.SENT) {
        return; // 그사이 다른 쪽이 끝냈다(상태는 앞으로만)
      }
      Instant now = clock.instant();
      if (outcome == DeliveryOutcome.DELIVERED) {
        // 전달 증거는 그사이 다른 작업자가 다시 가져갔어도 남긴다(아직 SENT일 때만, 상태는 앞으로만)
        notice.markDelivered(deliveredAt(now));
        return;
      }
      if (!poll.lease().equals(notice.getNextPollAt())) {
        // 그사이 다른 작업자가 다시 가져갔다. 오래된 결과 없음·조회 실패·실패로 최신 작업자의 결과를 앞지르지 않게 아무것도 적지 않는다
        return;
      }
      if (outcome == DeliveryOutcome.FAILED) {
        notice.markDeliveryFailed(now);
        return;
      }
      boolean pastDeadline =
          poll.pastDeadline()
              || !now.isBefore(DeliveryPollSchedule.deadline(notice.getSentAt(), giveUpAfter));
      if (pastDeadline) {
        // 결과 없음 또는 조회 실패인 채 기한이 지났다: 전달 확인 기한 만료로 끝낸다(실제 전달 실패의 증거는 아님)
        notice.markDeliveryFailed(now);
      }
      // 기한 전이면 그대로 둔다. 다음 조회 시각은 가져갈 때 이미 옮겼다
    });
  }

  /** 전달로 적을 시각. 지금은 서버가 업체의 전달 결과를 확인한 시각을 쓴다. 팀 결정 전 후보라(#120, d4 PR 머지 전 확인) 바꾸게 되면 여기만 고친다. */
  private static Instant deliveredAt(Instant confirmedAt) {
    return confirmedAt;
  }
}
