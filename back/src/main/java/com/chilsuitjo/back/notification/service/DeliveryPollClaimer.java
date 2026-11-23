package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.ClaimedDeliveryPoll;
import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전달 결과를 물을 통지를 가져간다(#120 3-A). 한 트랜잭션에서 시각이 된 보낸 통지를 {@code SKIP LOCKED}로 잠그고, 다음에 물을 시각을 옮기고, 커밋한
 * 뒤 돌려준다. 업체 조회는 돌려받은 뒤 트랜잭션 밖에서 한다(3-B).
 *
 * <p>트랜잭션은 {@link TransactionTemplate}로 이 메서드 안에서 연다({@code @Transactional}을 쓰면 같은 객체 안에서 부를 때 걸리지
 * 않을 수 있음). 새 트랜잭션({@code REQUIRES_NEW})이라 부르는 쪽에 트랜잭션이 있어도 돌려줄 때는 이미 커밋돼 있다.
 *
 * <p>업체 조회 중에 앱이 멈추면 옮긴 시각이 되었을 때 다시 가져가 묻는다(조회는 다시 해도 문자가 두 번 가지 않음).
 */
@Service
public class DeliveryPollClaimer {

  private static final Logger log = LoggerFactory.getLogger(DeliveryPollClaimer.class);

  private final NoticeRepository notices;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final Duration giveUpAfter;
  private final DeliveryPollSchedule schedule;

  public DeliveryPollClaimer(
      NoticeRepository notices,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.delivery.unknown-after:3d}") Duration giveUpAfter,
      DeliveryPollSchedule schedule) {
    this.notices = notices;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.giveUpAfter = giveUpAfter;
    this.schedule = schedule;
  }

  /**
   * 시각이 된 보낸 통지를 많아야 {@code limit}개 가져간다. 없으면 빈 목록.
   *
   * <p>보낸 시각이나 묶음 ID가 없는 보낸 통지는 {@code markSent}로는 생기지 않지만 V1이 막지는 않는다. 그런 줄은 돌려주지 않고(업체에 물을 값이 없음)
   * 상태도 바꾸지 않으며, 다음 조회 시각만 옮겨 다른 통지를 막지 않게 하고 로그를 남긴다.
   */
  public List<ClaimedDeliveryPoll> claim(int limit) {
    return transaction.execute(status -> {
      // DB(timestamptz)는 마이크로초까지라 지금 시각을 먼저 맞춘다. 그래야 적은 다음 조회 시각이 DB 값과 같고(가져간 세대 표시),
      // 다시 가져갈 때의 다음 시각이 언제나 앞 세대보다 커진다(앞 세대 <= 지금 < 다음 시각)
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      List<ClaimedDeliveryPoll> claimed = new ArrayList<>();
      for (Notice notice : notices.lockDuePolls(now, limit)) {
        Instant deadline =
            notice.getSentAt() == null
                ? null
                : DeliveryPollSchedule.deadline(notice.getSentAt(), giveUpAfter);
        if (deadline != null && notice.hasProtectedDeliveryPoll(now, deadline)) {
          notice.scheduleNextPoll(notice.getDeliveryPollProtectedUntil());
          continue;
        }
        if (notice.getSentAt() == null || notice.getBatchLeadId() == null) {
          notice.scheduleNextPoll(now.plus(schedule.interval()));
          log.error("보낸 시각 또는 묶음 ID가 없는 보낸 통지라 전달 결과를 묻지 않음: notice={}", notice.getId());
          continue;
        }
        boolean pastDeadline = !now.isBefore(deadline);
        // 정상 조회 일정은 기한을 넘지 않는다. 기한이 지난 통지는 다음 조회가 없고 이번 조회 뒤 최종 결과로 끝낸다(DeliveryResultPoller).
        // 그때 옮기는 시각은 조회 기한 연장이 아니라, 그 처리가 중간에 멈췄을 때만 다시 가져갈 복구용 예약이다
        Instant next =
            schedule
                .next(notice.getSentAt(), now, giveUpAfter)
                .orElse(now.plus(schedule.interval()));
        UUID token = UUID.randomUUID();
        notice.claimDeliveryPoll(token, next);
        claimed.add(
            new ClaimedDeliveryPoll(
                notice.getId(),
                new DeliveryQuery(notice.getBatchLeadId(), notice.getProviderMessageId()),
                pastDeadline,
                next,
                token));
      }
      return claimed;
    });
  }
}
