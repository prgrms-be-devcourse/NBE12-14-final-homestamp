package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.CloseAllScope;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
import com.chilsuitjo.back.notification.rule.SoloRule;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응답 요청을 DB({@code state.response_request})에 두는 실제 {@link WaitService}·{@link WaitQuery}. 쓰기와 조회가
 * 같은 행을 본다.
 *
 * <p><b>부르는 법.</b> 바꾸는 메서드는 원인 행동의 {@code StateStore.save} 안에서만 부른다({@code MANDATORY}). pipeline이
 * 호실 행을 먼저 잠근 트랜잭션이라 한 호실의 요청 바꾸기는 줄을 서고, 행동이 되돌아가면 요청도 함께 되돌아간다. 만든 시각·닫힌 시각은 그 행동의 시각({@link
 * ActionRequestContext#at()})이다(decisions 2026-11-19). 기다림 시작은 이 시각이 아니라 통지가 전달된 시각이다.
 *
 * <p><b>아직 하지 않는 것.</b>
 *
 * <ul>
 *   <li>통지·문자: 요청을 열어도 통지를 만들지 않는다(요청 → 통지 연결은 NOTI-1, #100). 그래서 {@code first_notified_at}·기한은 비어
 *       있고 요청은 {@code WAITING}에서 넘어가지 않는다
 *   <li>기한 다시 세기({@code restart}의 "다시 센다"): 기한을 정하는 코드가 아직 없어 기한이 있으면 조용히 옛 기한을 남기지 않고 {@code
 *       IllegalStateException}이다(기다림 타이머 d3에서 정함)
 *   <li>{@link #closeAll}: 상대 탈퇴만 닫고, 거주 종료(문서끼리 범위가 다름)·공유 중단·정산 완료는 #113에서 정하기 전이라 {@code
 *       UnsupportedOperationException}이다
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class JpaWaitService implements WaitService, WaitQuery {

  private static final Set<RequestStatus> OPEN =
      EnumSet.of(RequestStatus.WAITING, RequestStatus.UNCONFIRMED);

  private final ResponseRequestRepository requests;
  private final CounterpartReader counterparts;
  private final ActionRequestContext requestContext;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public RequestHandle open(OpenRequestCommand command) {
    Optional<ResponseRequest> existing =
        openRequest(command.kind(), command.target(), command.requestedBySide());
    if (existing.isPresent()) {
      ResponseRequest request = existing.get();
      if (command.kind() == RequestKind.PROPOSAL
          && request.getRequestedBySide() != command.requestedBySide()) {
        // 상대 안이 기다리는 중에 낸 안은 역제안이다. 이전 요청을 닫지 않고 열면 그 요청이 남아 기다림이 끝나지 않는다
        throw new IllegalStateException(
            "상대 쪽 제안 요청이 열려 있어요. 역제안이면 먼저 answered로 닫아 주세요(05 proposal ③)");
      }
      return new RequestHandle(request.getId(), false);
    }
    ResponseRequest created =
        requests.save(
            new ResponseRequest(
                command.roomRecordId(),
                command.kind(),
                command.target(),
                command.requestedBySide(),
                requestContext.at()));
    return new RequestHandle(created.getId(), true);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void answered(RequestTarget target, RequestKind kind, PartySide answeredBySide) {
    // 답한 측의 상대가 보낸 요청만 답이다. 점검 확인은 이것으로 방향을 고른다
    PartySide requestedBySide = counterpartOf(answeredBySide);
    openRequests(kind, target).stream()
        .filter(r -> r.getRequestedBySide() == requestedBySide)
        .forEach(r -> r.answer(answeredBySide));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void withdrawn(RequestTarget target, RequestKind kind, PartySide requestedBySide) {
    openRequests(kind, target).stream()
        .filter(r -> r.getRequestedBySide() == requestedBySide)
        .forEach(r -> r.withdraw(requestedBySide));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public RequestHandle restart(OpenRequestCommand command) {
    if (!canOpen(command.roomRecordId(), command.kind())) {
      throw new IllegalStateException(command.kind() + "는 지금 호실 단계에서 열 수 없어요. canOpen으로 먼저 막아 주세요");
    }
    Optional<ResponseRequest> existing =
        openRequest(command.kind(), command.target(), command.requestedBySide());
    if (existing.isEmpty()) {
      // 요청이 없거나 닫혔으면(답함·닫힘·거둠) 되살리지 않고 새로 연다(03 1-4 "닫힌 요청을 자동으로 되살리지 않는다")
      return open(command);
    }
    ResponseRequest request = existing.get();
    if (command.kind() == RequestKind.PROPOSAL) {
      if (request.getRequestedBySide() != command.requestedBySide()) {
        throw new IllegalStateException(
            "상대 쪽 제안 요청이 열려 있어요. 역제안이면 answered 다음 open을 불러 주세요(05 proposal ③)");
      }
      request.rewait();
    } else if (command.kind() == RequestKind.CORRECTION_CONFIRM
        && request.getStatus() == RequestStatus.UNCONFIRMED) {
      // 미확인 뒤 같은 기록을 또 고치면 새 요청을 열지 않고 같은 요청이 다시 기다림이 된다(03 1-4 "미확인 뒤 다시 고침", #104)
      request.rewait();
    } else if (!SoloRule.opensSolo(command.kind())) {
      // 고친 내용 확인 등은 마지막으로 더한 시각부터 다시 센다(03 1-4 "기한"). 기한을 정하는 타이머 전이라 기한이 있으면 거절한다.
      // 혼자 진행이 열리는 종류는 기한이 보낸 뒤 고정이라 묶기만 한다(미확인이어도 상태 그대로)
      request.requireNoDeadline();
    }
    return new RequestHandle(request.getId(), false);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void close(RequestTarget target, RequestKind kind, ClosedReason reason) {
    // 점검 확인은 두 방향이 같은 대상·종류라 둘 다 닫는다(WaitService#close)
    List<ResponseRequest> open = openRequests(kind, target);
    if (!open.isEmpty()) {
      var at = requestContext.at();
      open.forEach(r -> r.close(reason, at));
    }
  }

  /**
   * 정해진 범위({@link CloseAllScope}: 상대 탈퇴)만 닫는다. 그 밖의 이유는 #113에서 정하기 전이라 {@code
   * UnsupportedOperationException}이다(조용히 0을 돌려주면 닫히지 않은 요청이 남음). 닫힌 시각은 행동 시각이고, 부르는 쪽이 호실을 잠근
   * {@code save} 안에서 부른다.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int closeAll(UUID roomRecordId, ClosedReason reason, Set<RequestKind> kinds) {
    Set<RequestStatus> statuses = CloseAllScope.statuses(reason, kinds);
    List<ResponseRequest> open = requests.findInRoom(roomRecordId, kinds, statuses);
    if (!open.isEmpty()) {
      var at = requestContext.at();
      open.forEach(r -> r.close(reason, at));
    }
    return open.size();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean canOpen(UUID roomRecordId, RequestKind kind) {
    // 호실 단계는 측과 상관없다. read의 측은 판단에 쓰지 않는다
    return counterparts.read(roomRecordId, PartySide.TENANT).canOpen(kind);
  }

  @Override
  @Transactional(readOnly = true)
  public SoloCheck canGoSolo(SoloQuery query) {
    Optional<ResponseRequest> request =
        openRequest(query.kind(), query.target(), query.requestedBySide())
            .filter(r -> r.getRequestedBySide() == query.requestedBySide());
    if (request.isEmpty()) {
      return SoloCheck.notYet(null);
    }
    ResponseRequest r = request.get();
    if (r.getStatus() == RequestStatus.UNCONFIRMED) {
      SoloReason reason = r.getSoloReason() != null ? r.getSoloReason() : SoloReason.UNCONFIRMED;
      return r.getUnconfirmedDueAt() != null
          ? SoloCheck.allowedSince(reason, r.getUnconfirmedDueAt())
          : SoloCheck.allowedBecause(reason);
    }
    return SoloCheck.notYet(r.getUnconfirmedDueAt());
  }

  @Override
  @Transactional(readOnly = true)
  public SoloCheck canGoSoloDirectly(SoloQuery query) {
    return SoloRule.direct(
        counterparts.read(query.roomRecordId(), query.requestedBySide()), query.kind());
  }

  @Override
  @Transactional(readOnly = true)
  public List<RequestView> awaiting(UUID roomRecordId, PartySide answeringSide) {
    return requests.findOpenRequestedBy(roomRecordId, counterpartOf(answeringSide)).stream()
        .map(JpaWaitService::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RequestView> latest(RequestKind kind, RequestTarget target) {
    if (kind == RequestKind.INSPECTION_CONFIRM) {
      throw new IllegalArgumentException(kind + "는 방향이 둘이라 latest로 읽지 않아요");
    }
    return requests.findLatest(kind, target).map(JpaWaitService::view);
  }

  /** 대상·종류의 열린 요청. 점검 확인은 두 방향이 있을 수 있다. */
  private List<ResponseRequest> openRequests(RequestKind kind, RequestTarget target) {
    return requests.findByStatusIn(kind, target, OPEN);
  }

  /**
   * 새로 열지 묶을지 정할 때 보는 열린 요청 하나. 점검 확인은 요청한 측까지 보고, 그 밖은 대상·종류로 하나다(V1 유니크, 미확인도 열린 요청으로 봄). 둘 이상이면
   * 제약이 깨진 것이라 조용히 고르지 않는다.
   */
  private Optional<ResponseRequest> openRequest(
      RequestKind kind, RequestTarget target, PartySide requestedBySide) {
    List<ResponseRequest> open = openRequests(kind, target);
    if (kind == RequestKind.INSPECTION_CONFIRM) {
      open = open.stream().filter(r -> r.getRequestedBySide() == requestedBySide).toList();
    }
    if (open.size() > 1) {
      throw new IllegalStateException(kind + " 열린 요청이 " + open.size() + "건이에요");
    }
    return open.stream().findFirst();
  }

  private static RequestView view(ResponseRequest r) {
    return new RequestView(
        r.getKind(),
        r.target(),
        r.getRequestedBySide(),
        r.getStatus(),
        r.getCreatedAt(),
        r.getFirstNotifiedAt(),
        r.getUnconfirmedDueAt(),
        r.getSoloReason(),
        r.getClosedReason(),
        r.getClosedAt());
  }

  // global/common의 PartySide에 메서드를 더하면 모든 도메인의 공통 계약이 바뀌어 여기에만 둔다
  private static PartySide counterpartOf(PartySide side) {
    return side == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
  }
}
