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
 * <p>요청을 찾는 키는 V1의 "기다리는 요청은 하나" 제약과 같다. 대부분은 대상 + 종류이고, 점검 확인만 요청한 측까지 본다(점검 × 방향마다 하나, 03 H,
 * {@code ux_inspection_confirm_waiting}). 제안은 안건마다 기다리는 요청이 하나다({@code ux_proposal_waiting}). 상대의
 * 안에 낸 역제안은 이전 요청을 {@code answered}로 닫은 뒤 새로 연다(05 {@code proposal} ③). 닫지 않고 상대 쪽이 열면 거절한다.
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
            command.requestedBySide(),
            RequestStatus.WAITING,
            null);
    requests.put(key, created);
    return new RequestHandle(created.id(), true);
  }

  @Override
  public void answered(RequestTarget target, RequestKind kind, PartySide answeredBySide) {
    // 답한 측의 반대가 요청한 측이다. 점검 확인은 이것으로 방향을 고른다
    Key key = Key.of(target, kind, counterpartOf(answeredBySide));
    requests.computeIfPresent(
        key,
        (k, request) ->
            request.status().isOpen() && request.requestedBySide() != answeredBySide
                ? request.with(RequestStatus.ANSWERED, null)
                : request);
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
      requests.put(key, existing.with(RequestStatus.WAITING, null));
      return new RequestHandle(existing.id(), false);
    }
    // 그 밖의 열린 요청(기다림·미확인)은 상태를 그대로 두고 묶는다. 혼자 진행이 열리는 종류는 미확인이면 혼자 진행도 그대로다(03 1-4
    // "열리는 요청의 기한은 보낸 뒤 고정"). 고친 내용 확인이 미확인일 때 다시 고치는 경우(03 1-4 "미확인 뒤 다시 고침")는 B와 확인 중이라 지금 동작을
    // 둔다.
    return new RequestHandle(existing.id(), false);
  }

  @Override
  public void close(RequestTarget target, RequestKind kind, ClosedReason reason) {
    // 점검 확인은 두 방향이 같은 대상·종류라 둘 다 닫는다(WaitService#close)
    for (Map.Entry<Key, Request> entry : requests.entrySet()) {
      Key key = entry.getKey();
      Request request = entry.getValue();
      if (key.kind() == kind && target.equals(key.target()) && request.status().isOpen()) {
        entry.setValue(request.with(RequestStatus.CLOSED, reason));
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
        entry.setValue(request.with(RequestStatus.CLOSED, reason));
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
                ? request.with(RequestStatus.UNCONFIRMED, null)
                : request);
  }

  /**
   * 이 호실에서 이 종류를 열 수 없게 만든다(예: 거주 종료 뒤 하자 확인). 가짜는 호실 단계를 읽지 않아 기본은 모두 열 수 있다. 실제 구현은 {@code
   * CounterpartReader}의 단계와 {@code RequestOpening}으로 판단한다.
   */
  public void blockOpening(UUID roomRecordId, RequestKind kind) {
    blocked.add(Key.opening(roomRecordId, kind));
  }

  /** 혼자 진행 판단 결과를 정해 둔다(상대 탈퇴·수신 거부 등 가짜가 계산하지 않는 이유). */
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
      PartySide requestedBySide,
      RequestStatus status,
      ClosedReason closedReason) {
    Request with(RequestStatus newStatus, ClosedReason reason) {
      return new Request(id, roomRecordId, requestedBySide, newStatus, reason);
    }
  }
}
