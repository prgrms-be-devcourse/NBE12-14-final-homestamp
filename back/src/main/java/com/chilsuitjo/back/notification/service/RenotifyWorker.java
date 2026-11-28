package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.RenotifyStateStore;
import com.chilsuitjo.back.notification.repository.RenotifyStateStore.RenotifySkipped;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.RenotifyTransitions;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 재통지 작업자(06 NOTI-5a, 03 1-1 "48시간 응답 없음 → 재통지"). 저장된 재통지 기한({@code renotify_due_at})이 지난 기다리는 요청마다
 * 서버 행동 {@code RENOTIFY}를 pipeline으로 남기고, 같은 트랜잭션에서 재통지 문자를 아웃박스에 넣는다({@link RenotifyStateStore}).
 * 문자는 {@link OutboxWorker}가 보내고, 전달되거나 열리면 {@link NoticeWaitStart#reached}가 미확인 기한을 적는다. 재통지를 만들거나
 * 보낸 것만으로는 미확인 기한이 생기지 않고, 재통지가 실패해도 미확인으로 바꾸지 않는다(미확인 작업자 {@code MARK_UNCONFIRMED}는 아직 없음).
 *
 * <p><b>대상.</b> 기한은 첫 통지가 닿을 때만 생긴다({@code ResponseRequest.startWaiting}). 그래서 전달 증거가 없는 요청은 후보가
 * 아니다. 요청 종류로 거르지 않는다(03 1-1 "모든 응답 필요"). 거르는 것은 호실 단계·자리·링크다({@link RenotifyStateStore#check}).
 * 요청당 한 번이다: 같은 요청이 다시 기다림이 되어도({@code rewait}) 재통지는 늘지 않고, 닫힌 뒤 새로 연 요청은 새 요청이라 따로 센다.
 *
 * <p><b>잠금·멱등.</b> pipeline이 호실을 잠근 뒤 접근 검증에서 다시 본다(멱등 조회보다 먼저라 재생도 지금 상태를 통과해야 함). 잠금 순서는 다른 행동과
 * 같다(호실 → 통지·요청, 이 작업자는 통지를 새로 넣기만 하고 잠그지 않음). 행동 키는 {@code renotify:<요청 ID>:1}(서버 주체, 04 "서버 행동은
 * 정해진 키"). 두 작업자가 같은 요청을 동시에 잡아도 호실 잠금으로 줄을 서고 뒤에 온 쪽은 재통지가 이미 있어 건너뛴다. 켜질 때 따라잡기(06 OPS-1a)도 같은
 * 길이라 다시 켜도 더 나가지 않는다.
 *
 * <p>트랜잭션은 요청마다 pipeline이 연다. 한 요청이 실패해도 나머지는 처리한다.
 */
@Service
public class RenotifyWorker {

  private static final Logger log = LoggerFactory.getLogger(RenotifyWorker.class);

  private static final Instant START_DUE = Instant.EPOCH;
  private static final UUID START_ID = new UUID(0L, 0L);

  private final ResponseRequestRepository requests;
  private final RenotifyStateStore store;
  private final ActionPipeline pipeline;
  private final Clock clock;

  public RenotifyWorker(
      ResponseRequestRepository requests,
      RenotifyStateStore store,
      ActionPipeline pipeline,
      Clock clock) {
    this.requests = requests;
    this.store = store;
    this.pipeline = pipeline;
    this.clock = clock;
  }

  /**
   * 지금 기한이 지난 후보를 모두 한 번씩 본다. 한 번에 {@code pageSize}개씩 읽고, 읽은 마지막 요청 뒤부터 이어 읽는다(건너뛴 요청이 뒤 요청을 막지
   * 않게).
   *
   * @return 이번에 재통지를 만든 요청 수
   */
  public int runOnce(int pageSize) {
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize는 1 이상이어야 해요");
    }
    Instant now = clock.instant();
    Instant afterDue = START_DUE;
    UUID afterId = START_ID;
    int created = 0;
    while (true) {
      List<ResponseRequest> page =
          requests.findRenotifyDue(
              RequestStatus.WAITING,
              now,
              afterDue,
              afterId,
              NoticeKind.RENOTIFY,
              Limit.of(pageSize));
      for (ResponseRequest request : page) {
        try {
          if (renotify(request.getRoomRecordId(), request.getId())) {
            created++;
          }
        } catch (RuntimeException e) {
          log.error("재통지 처리 실패: request={}, error={}", request.getId(), e.getClass().getName());
        }
      }
      if (page.size() < pageSize) {
        return created;
      }
      ResponseRequest last = page.getLast();
      afterDue = last.getRenotifyDueAt();
      afterId = last.getId();
    }
  }

  /** 한 요청. 잠근 뒤 확인을 통과해 재통지를 남겼으면 true. */
  private boolean renotify(UUID roomRecordId, UUID requestId) {
    try {
      pipeline.executeAll(
          ActionBatch.of(roomRecordId, Actor.server(), "renotify:" + requestId + ":1")
              .accessCheck(() -> store.check(roomRecordId, requestId))
              .step(
                  ActionType.RENOTIFY,
                  done -> ActionTarget.of(TargetKind.RESPONSE_REQUEST, requestId),
                  store,
                  RenotifyTransitions.INSTANCE));
      return true;
    } catch (RenotifySkipped skipped) {
      log.debug("재통지 건너뜀: request={}, reason={}", requestId, skipped.reason());
      return false;
    }
  }
}
