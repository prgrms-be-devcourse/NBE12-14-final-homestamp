package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 1주차 2일차 가짜(07 1주차 B). 메모리에만 두고 해시는 계산하지 않는다. {@code room_seq}와 {@code UNIQUE(actor_key,
 * idempotency_key)}만 실제처럼 흉내 낸다.
 *
 * <p>실제 구현이 생기면 이 클래스를 지운다(같은 타입 빈이 둘이면 주입이 실패한다). 테스트끼리 값이 섞이지 않게, 테스트마다 다른 호실 ID와 키를 쓴다.
 *
 * <p>실제와 다른 점: 트랜잭션 격리가 없어(커밋 전 기록도 보임) 동시 요청 테스트에는 쓰지 않는다. 대상의 호실이 맞는지(복합 FK)도 보지 않는다. 롤백되면 그
 * 트랜잭션에서 쓴 줄은 지우지만, 도메인의 가짜 저장소까지 되돌리지는 않는다.
 */
@Component
class FakeActionWriter implements ActionWriter, ActionReader {

  private final List<ActionEntry> entries = new ArrayList<>();
  private final Map<String, ActionEntry> byFullKey = new HashMap<>();
  private final Map<UUID, List<ActionEntry>> byRequest = new HashMap<>();
  private final Map<UUID, Long> lastSeq = new HashMap<>();
  private final Clock clock;

  FakeActionWriter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized ActionEntry write(ActionCommand c, UUID requestId) {
    String fullKey = c.actor().key() + "|" + c.idempotencyKey();
    if (byFullKey.containsKey(fullKey)) {
      throw new DataIntegrityViolationException("uq_action_idempotency: " + fullKey);
    }
    long seq = lastSeq.merge(c.roomRecordId(), 1L, Long::sum);
    ActionEntry entry =
        new ActionEntry(
            UUID.randomUUID(),
            c.roomRecordId(),
            seq,
            c.type(),
            c.actor().side(),
            c.actor().key(),
            c.idempotencyKey(),
            requestId,
            c.target(),
            c.seenVersionNo(),
            null,
            c.reason(),
            c.payload(),
            clock.instant());
    entries.add(entry);
    byFullKey.put(fullKey, entry);
    byRequest.computeIfAbsent(requestId, id -> new ArrayList<>()).add(entry);
    forgetOnRollback(entry, fullKey);
    return entry;
  }

  /** 실제 DB처럼, 트랜잭션이 되돌려지면 그 안에서 쓴 줄도 없앤다(여러 행동 중 하나가 실패하면 모두 없음). */
  private void forgetOnRollback(ActionEntry entry, String fullKey) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              synchronized (FakeActionWriter.this) {
                entries.remove(entry);
                byFullKey.remove(fullKey);
                byRequest.computeIfPresent(
                    entry.requestId(),
                    (id, list) -> {
                      list.remove(entry);
                      return list.isEmpty() ? null : list;
                    });
                lastSeq.computeIfPresent(entry.roomRecordId(), (room, seq) -> seq - 1);
              }
            }
          }
        });
  }

  @Override
  public synchronized List<ActionEntry> findRequest(String actorKey, String idempotencyKey) {
    ActionEntry first = byFullKey.get(actorKey + "|" + idempotencyKey);
    if (first == null) {
      return List.of();
    }
    // 뒤 행동은 주체가 다를 수 있다(딸린 행동은 server). 키가 아니라 request_id로 모은다(#66).
    return List.copyOf(byRequest.get(first.requestId()));
  }

  @Override
  public synchronized List<ActionEntry> findByTarget(ActionTarget target) {
    return entries.stream().filter(e -> target.equals(e.target())).toList();
  }

  @Override
  public synchronized List<ActionEntry> findByRoom(UUID roomRecordId) {
    return entries.stream().filter(e -> roomRecordId.equals(e.roomRecordId())).toList();
  }
}
