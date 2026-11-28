package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.repository.InAppNotificationRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료된 서비스 안 알림을 실제로 지운다(04 "켜질 때 따라잡기"의 "30일 지난 알림", 05 {@code in_app_notification} {@code
 * expires_at}). 목록·안 읽은 수·읽음은 이미 만료된 줄을 보지 않지만, 보이지 않는 것으로 끝내지 않고 행을 지운다.
 *
 * <ul>
 *   <li>한 번 돌 때 기준 시각은 하나다(돌던 중 새로 만료되는 줄은 다음 번)
 *   <li>묶음마다 따로 커밋한다. 한 묶음이 실패하면 그 묶음만 되돌아가고 앞 묶음은 남으며, 남은 줄은 다음 번에 다시 지운다
 *   <li>한 번에 최대 {@code batchSize × maxBatches}줄. 더 남으면 다음 번에 이어서 지운다
 *   <li>여러 번·여러 앱이 돌아도 같다(이미 지운 줄은 없고, 잠긴 줄은 건너뜀)
 * </ul>
 */
@Service
public class InAppNotificationCleanup {

  private static final Logger log = LoggerFactory.getLogger(InAppNotificationCleanup.class);

  private final InAppNotificationRepository notifications;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final int batchSize;
  private final int maxBatches;

  public InAppNotificationCleanup(
      InAppNotificationRepository notifications,
      TransactionTemplate transaction,
      Clock clock,
      @Value("${app.notification.cleanup.batch-size:500}") int batchSize,
      @Value("${app.notification.cleanup.max-batches:20}") int maxBatches) {
    if (batchSize < 1 || maxBatches < 1) {
      throw new IllegalArgumentException(
          "app.notification.cleanup.batch-size·max-batches는 1 이상이어야 해요");
    }
    this.notifications = notifications;
    this.transaction = transaction;
    this.clock = clock;
    this.batchSize = batchSize;
    this.maxBatches = maxBatches;
  }

  /**
   * 지금 만료된 알림을 지우고 지운 줄 수를 돌려준다.
   *
   * @return 이번에 지운 줄 수
   */
  public int purgeExpired() {
    Instant now = clock.instant();
    int total = 0;
    for (int batch = 0; batch < maxBatches; batch++) {
      Integer deleted = transaction.execute(status -> notifications.deleteExpired(now, batchSize));
      int count = deleted == null ? 0 : deleted;
      total += count;
      if (count < batchSize) {
        break;
      }
    }
    if (total > 0) {
      log.info("만료된 서비스 안 알림을 지움: count={}", total);
    }
    return total;
  }
}
