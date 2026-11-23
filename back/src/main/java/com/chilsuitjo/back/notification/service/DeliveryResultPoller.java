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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
  private final Duration protectFor;

  public DeliveryResultPoller(
      DeliveryPollClaimer claimer,
      NoticeRepository notices,
      SmsGateway gateway,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.delivery.unknown-after:3d}") Duration giveUpAfter,
      @Value("${app.delivery.poller.protect-for:2m}") Duration protectFor) {
    if (protectFor == null
        || protectFor.isNegative()
        || protectFor.isZero()
        || protectFor.compareTo(Duration.ofNanos(1_000)) < 0
        || protectFor.getNano() % 1_000 != 0
        || protectFor.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException(
          "app.delivery.poller.protect-for는 마이크로초 이상 15분 이하이고 마이크로초 단위여야 해요");
    }
    this.claimer = claimer;
    this.notices = notices;
    this.gateway = gateway;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.giveUpAfter = giveUpAfter;
    this.protectFor = protectFor;
  }

  /** 많아야 {@code limit}개를 가져가 묻고, 가져간 통지 수를 돌려준다. 한 통지에서 예외가 나도 나머지는 적는다. */
  public int runOnce(int limit) {
    List<ClaimedDeliveryPoll> claimed = claimer.claim(limit);
    if (claimed.isEmpty()) {
      return 0;
    }
    StartedBatch started = beginBatch(claimed);
    if (started.polls().isEmpty()) {
      return claimed.size();
    }
    QueriedResults queried = query(started.queries());
    for (ClaimedDeliveryPoll poll : started.polls()) {
      try {
        // 업체가 이 묶음 ID를 빠뜨렸으면 결과 없음과 같다. 조회 자체가 실패했으면 결과 맵이 null이라 이번에는 아무 결과도 모른다
        DeliveryOutcome outcome =
            queried.results() == null ? null : queried.results().get(poll.query().batchLeadId());
        record(poll, outcome, queried.observedAt());
      } catch (RuntimeException e) {
        log.error("전달 결과 저장 실패: notice={}, error={}", poll.noticeId(), e.getClass().getName());
      }
    }
    return claimed.size();
  }

  /**
   * 한 묶음의 논리적 조회 시작을 한 트랜잭션에서 승인한다. 모든 통지를 잠근 뒤 공통 시각을 기록하고, 커밋 직후 외부 Gateway를 부른다. DB 커밋과 네트워크 호출은
   * 원자적이지 않으므로 이 공통 시각이 batch 조회의 논리적 시작이다. 회수된 세대는 제외한다.
   */
  private StartedBatch beginBatch(List<ClaimedDeliveryPoll> polls) {
    StartedBatch started =
        transaction.execute(status -> {
          List<PollCandidate> candidates = new ArrayList<>(polls.size());
          for (ClaimedDeliveryPoll poll : polls) {
            notices
                .findForUpdate(poll.noticeId())
                .ifPresent(notice -> candidates.add(new PollCandidate(poll, notice)));
          }

          Instant batchStartedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
          Instant protectedUntil = batchStartedAt.plus(protectFor);
          List<ClaimedDeliveryPoll> active = new ArrayList<>(candidates.size());
          List<DeliveryQuery> queries = new ArrayList<>(candidates.size());
          for (PollCandidate candidate : candidates) {
            if (candidate
                .notice()
                .startDeliveryPoll(candidate.poll().token(), batchStartedAt, protectedUntil)) {
              active.add(candidate.poll());
              queries.add(candidate.poll().query());
            }
          }
          return new StartedBatch(List.copyOf(active), List.copyOf(queries));
        });
    return started == null ? new StartedBatch(List.of(), List.of()) : started;
  }

  private record PollCandidate(ClaimedDeliveryPoll poll, Notice notice) {}

  private record StartedBatch(List<ClaimedDeliveryPoll> polls, List<DeliveryQuery> queries) {}

  /** 한 번의 묶음 응답을 확인한 서버 시각. 결과 조회 실패에는 전달 관측 시각이 없다. */
  private record QueriedResults(Map<UUID, DeliveryOutcome> results, Instant observedAt) {}

  /** 업체에 묻는다. 조회 자체가 실패하면 결과 맵은 null(결과 없음과 다름). */
  private QueriedResults query(List<DeliveryQuery> queries) {
    Map<UUID, DeliveryOutcome> results;
    try {
      results = gateway.queryResults(queries);
    } catch (RuntimeException e) {
      log.warn("전달 결과 조회 실패: count={}, error={}", queries.size(), e.getClass().getName());
      return new QueriedResults(null, null);
    }
    return new QueriedResults(results, clock.instant());
  }

  /**
   * 통지를 다시 잠그고 결과를 적는다. 기한은 가져간 시각({@code pastDeadline})만 믿지 않고 적는 지금 다시 본다(업체에 묻는 사이 기한이 지날 수 있음).
   *
   * <p>조회 토큰과 보호 종료 시각이 아직 유효할 때만 결과를 적는다. 기한 전에 시작한 조회의 보호 중 다른 작업자가 예약 시각을 옮겨도 명시적 결과는 적을 수 있다.
   * 보호 종료 뒤 회수된 조회의 늦은 응답은 버린다.
   *
   * @param outcome 업체 결과. null이면 조회 실패로 결과를 모름
   */
  private void record(ClaimedDeliveryPoll poll, DeliveryOutcome outcome, Instant observedAt) {
    transaction.executeWithoutResult(status -> {
      Notice notice = notices.findForUpdate(poll.noticeId()).orElse(null);
      if (notice == null || notice.getStatus() != NoticeStatus.SENT) {
        return; // 그사이 다른 쪽이 끝냈다(상태는 앞으로만)
      }
      Instant now = clock.instant();
      if (!notice.ownsDeliveryPoll(poll.token(), now)) {
        return;
      }
      if (outcome == DeliveryOutcome.DELIVERED) {
        // 보호 중 다른 작업자가 예약 시각을 옮겼더라도 같은 토큰의 전달 증거는 남긴다.
        notice.markDelivered(observedAt);
        return;
      }
      if (outcome == DeliveryOutcome.FAILED) {
        // 같은 조회의 명시적 최종 실패는 기한 만료 보호를 위한 예약 시각 이동과 무관하다.
        notice.markDeliveryFailed(now);
        return;
      }
      boolean pastDeadline =
          poll.pastDeadline()
              || !now.isBefore(DeliveryPollSchedule.deadline(notice.getSentAt(), giveUpAfter));
      if (pastDeadline) {
        // 결과 없음 또는 조회 실패인 채 기한이 지났다: 전달 확인 기한 만료로 끝낸다(실제 전달 실패의 증거는 아님)
        notice.markDeliveryFailed(now);
      } else {
        notice.finishDeliveryPoll(poll.token(), poll.lease());
      }
      // 기한 전이면 그대로 둔다. 다음 조회 시각은 가져갈 때 이미 옮겼다
    });
  }
}
