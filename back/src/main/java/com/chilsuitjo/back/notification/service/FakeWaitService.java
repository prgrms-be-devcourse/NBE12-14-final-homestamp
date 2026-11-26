package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
import com.chilsuitjo.back.notification.rule.SoloRule;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 응답 요청 가짜(07 D 3일차). DB·타이머·문자 없이 메모리에 요청 상태만 적는다. 다른 도메인이 {@code save}에서 부르는 모양과 "열린 요청만 닫고 닫힌
 * 요청은 다시 열지 않는다" 규칙은 진짜와 같다.
 *
 * <p>요청을 찾는 키는 V1의 "기다리는 요청은 하나" 제약과 같다. 대부분은 대상 + 종류이고, 점검 확인만 요청한 측까지 본다(점검 × 방향마다 하나, 03 H,
 * {@code ux_inspection_confirm_waiting}). 제안은 안건마다 기다리는 요청이 하나다({@code ux_proposal_waiting}). 상대의
 * 안에 낸 역제안은 이전 요청을 {@code answered}로 닫은 뒤 새로 연다(05 {@code proposal} ③). 닫지 않고 상대 쪽이 열면 거절한다.
 *
 * <p><b>바로 혼자 진행({@link #canGoSoloDirectly})은 가짜가 아니다.</b> 진짜 {@link CounterpartReader}로 읽은 자리 사실을
 * {@link SoloRule}에 넘긴다. 메모리 요청·{@link #seedSolo}·{@link #markUnconfirmed}은 보지 않는다. 이 클래스가 운영에서도
 * 쓰이는 동안(진짜 구현 전) 테스트에서 만든 근거로 혼자 진행이 열리지 않게 하려고다.
 *
 * <p>진짜 구현은 요청을 DB에 두어 부르는 쪽 트랜잭션과 함께 되돌아간다. 가짜도 같게, 트랜잭션 안에서 바꾼 요청은 그 트랜잭션이 커밋되지 않으면 바꾸기 전으로
 * 되돌린다(한 요청의 뒤 단계가 거절되면 앞 단계가 닫은 요청도 열린 채로 남는다, #177 리뷰). 트랜잭션 밖(단위 테스트)에서는 바로 적는다.
 *
 * <p><b>조회({@link WaitQuery})도 같은 메모리 요청을 읽는다.</b> 쓰기와 읽기가 같은 상태를 본다. 가짜는 통지·전달·타이머가 없어서 {@code
 * firstNotifiedAt}·{@code dueAt}·{@code soloReason}을 늘 비운다(보내지 않은 통지의 시각이나 기한을 만들어 내지 않는다). {@link
 * #markUnconfirmed}로 바꾼 미확인도 이유는 비운다. {@code openedAt}은 처음 연 시각이고 묶어도 바뀌지 않는다.
 *
 * <p>실제 구현(NOTI-1·5, 기다림 타이머)이 들어오면 이 클래스를 지운다. 다른 도메인은 {@link WaitService}만 쓰므로 바뀌지 않는다.
 */
@Service
public class FakeWaitService implements WaitService, WaitQuery {

  private final Map<Key, Request> requests = new ConcurrentHashMap<>();
  private final Set<Key> blocked = ConcurrentHashMap.newKeySet();
  private final Map<Key, SoloCheck> soloChecks = new ConcurrentHashMap<>();
  private final CounterpartReader counterparts;
  private final Clock clock;

  public FakeWaitService(CounterpartReader counterparts, Clock clock) {
    this.counterparts = counterparts;
    this.clock = clock;
  }

  @Override
  public RequestHandle open(OpenRequestCommand command) {
    Key key = Key.of(command.target(), command.kind(), command.requestedBySide());
    Request existing = requests.get(key);
    if (existing != null && existing.status().isOpen()) {
      if (command.kind() == RequestKind.PROPOSAL
          && existing.requestedBySide() != command.requestedBySide()) {
        // 상대 안이 기다리는 중에 낸 안은 역제안이다. 이전 요청을 닫지 않고 열면 그 요청이 남아 기다림이 끝나지 않는다
        throw new IllegalStateException(
            "상대 쪽 제안 요청이 열려 있어요. 역제안이면 먼저 answered로 닫아 주세요(05 proposal ③)");
      }
      return new RequestHandle(existing.id(), false);
    }
    Request created =
        new Request(
            UUID.randomUUID(),
            command.roomRecordId(),
            command.kind(),
            command.target(),
            command.requestedBySide(),
            RequestStatus.WAITING,
            null,
            clock.instant(),
            null);
    write(key, created);
    return new RequestHandle(created.id(), true);
  }

  @Override
  public void answered(RequestTarget target, RequestKind kind, PartySide answeredBySide) {
    // 답한 측의 반대가 요청한 측이다. 점검 확인은 이것으로 방향을 고른다
    Key key = Key.of(target, kind, counterpartOf(answeredBySide));
    Request request = requests.get(key);
    if (request != null
        && request.status().isOpen()
        && request.requestedBySide() != answeredBySide) {
      write(key, request.with(RequestStatus.ANSWERED));
    }
  }

  @Override
  public void withdrawn(RequestTarget target, RequestKind kind, PartySide requestedBySide) {
    Key key = Key.of(target, kind, requestedBySide);
    Request request = requests.get(key);
    if (request != null
        && request.status().isOpen()
        && request.requestedBySide() == requestedBySide) {
      write(key, request.with(RequestStatus.WITHDRAWN));
    }
  }

  @Override
  public RequestHandle restart(OpenRequestCommand command) {
    Key key = Key.of(command.target(), command.kind(), command.requestedBySide());
    if (!canOpen(command.roomRecordId(), command.kind())) {
      throw new IllegalStateException(command.kind() + "는 지금 호실 단계에서 열 수 없어요. canOpen으로 먼저 막아 주세요");
    }
    Request existing = requests.get(key);
    if (existing == null || !existing.status().isOpen()) {
      // 요청이 없거나 닫혔으면(답함·닫힘·거둠) 되살리지 않고 새로 연다(03 1-4 "닫힌 요청을 자동으로 되살리지 않는다")
      return open(command);
    }
    if (command.kind() == RequestKind.PROPOSAL) {
      if (existing.requestedBySide() != command.requestedBySide()) {
        // 상대 안에 대한 새 안은 바꾸기(②)가 아니라 역제안(③): answered 다음 open(05 proposal)
        throw new IllegalStateException(
            "상대 쪽 제안 요청이 열려 있어요. 역제안이면 answered 다음 open을 불러 주세요(05 proposal ③)");
      }
      // 낸 쪽이 바꾸면 같은 요청이 다시 기다림이 된다(05 proposal ②, 03 E). 안건마다 기다리는 요청이 하나라 새로 열 수 없다
      write(key, existing.with(RequestStatus.WAITING));
      return new RequestHandle(existing.id(), false);
    }
    // 그 밖의 열린 요청(기다림·미확인)은 상태를 그대로 두고 묶는다. 혼자 진행이 열리는 종류는 미확인이면 혼자 진행도 그대로다(03 1-4
    // "열리는 요청의 기한은 보낸 뒤 고정"). 고친 내용 확인이 미확인일 때 다시 고치는 경우(03 1-4 "미확인 뒤 다시 고침")는 아직 정하지 않아 지금 동작을
    // 둔다(#104).
    return new RequestHandle(existing.id(), false);
  }

  @Override
  public void close(RequestTarget target, RequestKind kind, ClosedReason reason) {
    // 점검 확인은 두 방향이 같은 대상·종류라 둘 다 닫는다(WaitService#close)
    for (Map.Entry<Key, Request> entry : requests.entrySet()) {
      Key key = entry.getKey();
      Request request = entry.getValue();
      if (key.kind() == kind && target.equals(key.target()) && request.status().isOpen()) {
        write(key, request.closed(reason, clock.instant()));
      }
    }
  }

  @Override
  public int closeAll(UUID roomRecordId, ClosedReason reason, Set<RequestKind> kinds) {
    int closed = 0;
    for (Map.Entry<Key, Request> entry : requests.entrySet()) {
      Request request = entry.getValue();
      if (request.roomRecordId().equals(roomRecordId)
          && kinds.contains(entry.getKey().kind())
          && request.status().isOpen()) {
        write(entry.getKey(), request.closed(reason, clock.instant()));
        closed++;
      }
    }
    return closed;
  }

  @Override
  public boolean canOpen(UUID roomRecordId, RequestKind kind) {
    return !blocked.contains(Key.opening(roomRecordId, kind));
  }

  @Override
  public SoloCheck canGoSolo(SoloQuery query) {
    Key key = Key.of(query.target(), query.kind(), query.requestedBySide());
    SoloCheck seeded = soloChecks.get(key);
    if (seeded != null) {
      return seeded;
    }
    Request request = requests.get(key);
    if (request != null && request.status() == RequestStatus.UNCONFIRMED) {
      return SoloCheck.allowedBecause(SoloReason.UNCONFIRMED);
    }
    return SoloCheck.notYet(null);
  }

  @Override
  public SoloCheck canGoSoloDirectly(SoloQuery query) {
    return SoloRule.direct(
        counterparts.read(query.roomRecordId(), query.requestedBySide()), query.kind());
  }

  @Override
  public List<RequestView> awaiting(UUID roomRecordId, PartySide answeringSide) {
    PartySide requestedBySide = counterpartOf(answeringSide);
    return requests.values().stream()
        .filter(r -> r.roomRecordId().equals(roomRecordId))
        .filter(r -> r.requestedBySide() == requestedBySide)
        .filter(r -> r.status().isOpen())
        .map(Request::view)
        .toList();
  }

  @Override
  public Optional<RequestView> latest(RequestKind kind, RequestTarget target) {
    if (Key.hasDirection(kind)) {
      throw new IllegalArgumentException(kind + "는 방향이 둘이라 latest로 읽지 않아요");
    }
    // 키마다 가장 최근에 연 요청 하나만 남는다(닫힌 뒤 새로 열면 덮어씀)
    return Optional.ofNullable(requests.get(Key.of(target, kind, null))).map(Request::view);
  }

  // ---- 테스트·로컬에서 상황을 만드는 도구 ----
  // 점검 확인은 방향이 둘이라 요청한 측을 받는 쪽을 쓴다. 방향 없이 물으면 어느 쪽인지 정할 수 없어 거절한다

  /**
   * 이 대상·종류의 요청 상태. 요청이 없으면 빈 값. 점검 확인은 {@link #statusOf(RequestTarget, RequestKind, PartySide)}.
   */
  public Optional<RequestStatus> statusOf(RequestTarget target, RequestKind kind) {
    return statusOf(target, kind, requireNoDirection(kind));
  }

  /** 이 대상·종류·요청한 측의 요청 상태. 점검 확인이 아니면 요청한 측은 보지 않는다. */
  public Optional<RequestStatus> statusOf(
      RequestTarget target, RequestKind kind, PartySide requestedBySide) {
    return Optional.ofNullable(requests.get(Key.of(target, kind, requestedBySide)))
        .map(Request::status);
  }

  /** 닫힌 요청이면 닫힌 이유. */
  public Optional<ClosedReason> closedReasonOf(RequestTarget target, RequestKind kind) {
    return closedReasonOf(target, kind, requireNoDirection(kind));
  }

  /** 닫힌 요청이면 닫힌 이유(요청한 측까지 지정). */
  public Optional<ClosedReason> closedReasonOf(
      RequestTarget target, RequestKind kind, PartySide requestedBySide) {
    return Optional.ofNullable(requests.get(Key.of(target, kind, requestedBySide)))
        .map(Request::closedReason);
  }

  /** 72시간이 지나 미확인이 된 것으로 만든다(진짜는 타이머가 바꿈). */
  public void markUnconfirmed(RequestTarget target, RequestKind kind) {
    markUnconfirmed(target, kind, requireNoDirection(kind));
  }

  /** 72시간이 지나 미확인이 된 것으로 만든다(요청한 측까지 지정). */
  public void markUnconfirmed(RequestTarget target, RequestKind kind, PartySide requestedBySide) {
    requests.computeIfPresent(
        Key.of(target, kind, requestedBySide),
        (k, request) ->
            request.status() == RequestStatus.WAITING
                ? request.with(RequestStatus.UNCONFIRMED)
                : request);
  }

  /**
   * 이 호실에서 이 종류를 열 수 없게 만든다(예: 거주 종료 뒤 하자 확인). 가짜는 호실 단계를 읽지 않아 기본은 모두 열 수 있다. 실제 구현은 {@code
   * CounterpartReader}의 단계와 {@code RequestOpening}으로 판단한다.
   */
  public void blockOpening(UUID roomRecordId, RequestKind kind) {
    blocked.add(Key.opening(roomRecordId, kind));
  }

  /** 요청 기준 혼자 진행({@link #canGoSolo}) 결과를 정해 둔다. 바로 혼자 진행({@link #canGoSoloDirectly})에는 쓰이지 않는다. */
  public void seedSolo(RequestTarget target, RequestKind kind, SoloCheck check) {
    seedSolo(target, kind, requireNoDirection(kind), check);
  }

  /** 혼자 진행 판단 결과를 정해 둔다(요청한 측까지 지정). */
  public void seedSolo(
      RequestTarget target, RequestKind kind, PartySide requestedBySide, SoloCheck check) {
    soloChecks.put(Key.of(target, kind, requestedBySide), check);
  }

  private static PartySide requireNoDirection(RequestKind kind) {
    if (Key.hasDirection(kind)) {
      throw new IllegalArgumentException(kind + "는 방향이 둘이라 요청한 측을 함께 넘겨 주세요");
    }
    return null;
  }

  /** 요청 상태를 바꾼다. 트랜잭션 안이면 그 트랜잭션에서 처음 바꾸기 전 값을 기억해 두고, 커밋되지 않으면 되돌린다(진짜 구현의 DB 롤백과 같게). */
  private void write(Key key, Request next) {
    Request previous = requests.put(key, next);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      journal().remember(key, previous);
    }
  }

  /** 지금 트랜잭션의 되돌릴 목록. 없으면 만들어 그 트랜잭션에 건다(중단된 바깥 트랜잭션의 목록은 보이지 않는다). */
  private Journal journal() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof Journal journal && journal.owner() == this) {
        return journal;
      }
    }
    Journal journal = new Journal();
    TransactionSynchronizationManager.registerSynchronization(journal);
    return journal;
  }

  /** 한 트랜잭션에서 바꾼 요청의 처음 값. 커밋되지 않고 끝나면 그 값으로 되돌린다(없던 요청이면 지운다). */
  private final class Journal implements TransactionSynchronization {

    private final Map<Key, Optional<Request>> originals = new LinkedHashMap<>();

    FakeWaitService owner() {
      return FakeWaitService.this;
    }

    void remember(Key key, Request previous) {
      originals.putIfAbsent(key, Optional.ofNullable(previous));
    }

    @Override
    public void afterCompletion(int status) {
      if (status == STATUS_COMMITTED) {
        return;
      }
      originals.forEach((key, original) ->
          original.ifPresentOrElse(
              request -> requests.put(key, request), () -> requests.remove(key)));
    }
  }

  // global/common의 PartySide에 메서드를 더하면 모든 도메인의 공통 계약이 바뀌어 여기에만 둔다
  private static PartySide counterpartOf(PartySide side) {
    return side == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
  }

  /** 요청을 찾는 키. {@code requestedBySide}는 방향이 있는 종류(점검 확인)일 때만 채운다. */
  private record Key(RequestKind kind, RequestTarget target, PartySide requestedBySide, UUID room) {

    static boolean hasDirection(RequestKind kind) {
      return kind == RequestKind.INSPECTION_CONFIRM;
    }

    static Key of(RequestTarget target, RequestKind kind, PartySide requestedBySide) {
      return new Key(kind, target, hasDirection(kind) ? requestedBySide : null, null);
    }

    static Key opening(UUID roomRecordId, RequestKind kind) {
      return new Key(kind, null, null, roomRecordId);
    }
  }

  private record Request(
      UUID id,
      UUID roomRecordId,
      RequestKind kind,
      RequestTarget target,
      PartySide requestedBySide,
      RequestStatus status,
      ClosedReason closedReason,
      Instant createdAt,
      Instant closedAt) {

    /** 닫지 않는 상태 바꾸기. 닫힌 이유·시각은 닫을 때만 있다({@link #closed}). */
    Request with(RequestStatus newStatus) {
      if (newStatus == RequestStatus.CLOSED) {
        throw new IllegalArgumentException("닫기는 closed로 해 주세요");
      }
      return new Request(
          id, roomRecordId, kind, target, requestedBySide, newStatus, null, createdAt, null);
    }

    Request closed(ClosedReason reason, Instant at) {
      return new Request(
          id,
          roomRecordId,
          kind,
          target,
          requestedBySide,
          RequestStatus.CLOSED,
          reason,
          createdAt,
          at);
    }

    RequestView view() {
      return new RequestView(
          kind,
          target,
          requestedBySide,
          status,
          createdAt,
          null,
          null,
          null,
          closedReason,
          closedAt);
    }
  }
}
