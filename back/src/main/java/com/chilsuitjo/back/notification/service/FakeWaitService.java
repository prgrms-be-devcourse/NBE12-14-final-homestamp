package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 응답 요청 가짜(07 D 3일차). DB·타이머·문자 없이 메모리에 요청 상태만 적는다. 다른 도메인이 {@code save}에서 부르는 모양과 "열린 요청만 닫고 닫힌
 * 요청은 다시 열지 않는다" 규칙은 진짜와 같다.
 *
 * <p>실제 구현(NOTI-1·5, 기다림 타이머)이 들어오면 이 클래스를 지운다. 다른 도메인은 {@link WaitService}만 쓰므로 바뀌지 않는다.
 */
@Service
public class FakeWaitService implements WaitService {

  private final Map<Key, Request> requests = new ConcurrentHashMap<>();
  private final Set<Key> blocked = ConcurrentHashMap.newKeySet();
  private final Map<Key, SoloCheck> soloChecks = new ConcurrentHashMap<>();

  @Override
  public RequestHandle open(OpenRequestCommand command) {
    Key key = Key.of(command.target(), command.kind());
    Request existing = requests.get(key);
    if (existing != null && existing.status().isOpen()) {
      return new RequestHandle(existing.id(), false);
    }
    Request created =
        new Request(
            UUID.randomUUID(),
            command.roomRecordId(),
            command.requestedBySide(),
            RequestStatus.WAITING,
            null);
    requests.put(key, created);
    return new RequestHandle(created.id(), true);
  }

  @Override
  public void answered(RequestTarget target, RequestKind kind, PartySide answeredBySide) {
    Key key = Key.of(target, kind);
    requests.computeIfPresent(
        key,
        (k, request) ->
            request.status().isOpen() && request.requestedBySide() != answeredBySide
                ? request.with(RequestStatus.ANSWERED, null)
                : request);
  }

  @Override
  public RequestHandle restart(OpenRequestCommand command) {
    Key key = Key.of(command.target(), command.kind());
    if (!canOpen(command.roomRecordId(), command.kind())) {
      throw new IllegalStateException(command.kind() + "는 지금 호실 단계에서 열 수 없어요. canOpen으로 먼저 막아 주세요");
    }
    Request existing = requests.get(key);
    if (existing == null) {
      return open(command);
    }
    requests.put(key, existing.with(RequestStatus.WAITING, null));
    return new RequestHandle(existing.id(), false);
  }

  @Override
  public void close(RequestTarget target, RequestKind kind, ClosedReason reason) {
    requests.computeIfPresent(
        Key.of(target, kind),
        (k, request) ->
            request.status().isOpen() ? request.with(RequestStatus.CLOSED, reason) : request);
  }

  @Override
  public int closeAll(UUID roomRecordId, ClosedReason reason, Set<RequestKind> kinds) {
    int closed = 0;
    for (Map.Entry<Key, Request> entry : requests.entrySet()) {
      Request request = entry.getValue();
      if (request.roomRecordId().equals(roomRecordId)
          && kinds.contains(entry.getKey().kind())
          && request.status().isOpen()) {
        entry.setValue(request.with(RequestStatus.CLOSED, reason));
        closed++;
      }
    }
    return closed;
  }

  @Override
  public boolean canOpen(UUID roomRecordId, RequestKind kind) {
    return !blocked.contains(new Key(kind, null, roomRecordId));
  }

  @Override
  public SoloCheck canGoSolo(SoloQuery query) {
    Key key = Key.of(query.target(), query.kind());
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

  // ---- 테스트·로컬에서 상황을 만드는 도구 ----

  /** 이 대상·종류의 요청 상태. 요청이 없으면 빈 값. */
  public Optional<RequestStatus> statusOf(RequestTarget target, RequestKind kind) {
    return Optional.ofNullable(requests.get(Key.of(target, kind))).map(Request::status);
  }

  /** 닫힌 요청이면 닫힌 이유. */
  public Optional<ClosedReason> closedReasonOf(RequestTarget target, RequestKind kind) {
    return Optional.ofNullable(requests.get(Key.of(target, kind))).map(Request::closedReason);
  }

  /** 72시간이 지나 미확인이 된 것으로 만든다(진짜는 타이머가 바꿈). */
  public void markUnconfirmed(RequestTarget target, RequestKind kind) {
    requests.computeIfPresent(
        Key.of(target, kind),
        (k, request) ->
            request.status() == RequestStatus.WAITING
                ? request.with(RequestStatus.UNCONFIRMED, null)
                : request);
  }

  /** 이 호실에서 이 종류를 열 수 없게 만든다(예: 거주 종료 뒤 하자 확인). */
  public void blockOpening(UUID roomRecordId, RequestKind kind) {
    blocked.add(new Key(kind, null, roomRecordId));
  }

  /** 혼자 진행 판단 결과를 정해 둔다(상대 탈퇴·수신 거부 등 가짜가 계산하지 않는 이유). */
  public void seedSolo(RequestTarget target, RequestKind kind, SoloCheck check) {
    soloChecks.put(Key.of(target, kind), check);
  }

  private record Key(RequestKind kind, RequestTarget target, UUID roomRecordId) {
    static Key of(RequestTarget target, RequestKind kind) {
      return new Key(kind, target, null);
    }
  }

  private record Request(
      UUID id,
      UUID roomRecordId,
      PartySide requestedBySide,
      RequestStatus status,
      ClosedReason closedReason) {
    Request with(RequestStatus newStatus, ClosedReason reason) {
      return new Request(id, roomRecordId, requestedBySide, newStatus, reason);
    }
  }
}
