package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.correction.entity.CorrectionRequest;
import com.chilsuitjo.back.correction.repository.CorrectionRequestRepository;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.WaitService;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 다른 행동 때문에 한 기록의 기다리는 고친 내용을 답 없이 닫는다(05 2-5 {@code closed_reason}). 예: 처리 완료(양측 확인)가 되면 그 수리 건
 * 내용의 확인 전 고친 내용과 그 확인 요청을 {@code CLOSED_BY_BOTH}로 닫는다(06 REP-7l).
 *
 * <p>원인 행동의 {@code StateStore.save} 안에서 부른다. 그래서 pipeline이 연 트랜잭션과 호실 잠금 안에서 돌고, 닫힌 시각은 그 행동의 시각이다.
 * 고친 내용마다 행동을 따로 남기지 않는다(원인은 그 행동 기록).
 */
@Component
@RequiredArgsConstructor
public class CorrectionCloser {

  private final CorrectionRequestRepository corrections;
  private final WaitService waits;
  private final ActionRequestContext requestContext;

  /**
   * 그 기록의 기다리는 고친 내용을 모두 닫고, 고친 내용 확인 요청({@code CORRECTION_CONFIRM}, 기록당 하나)도 같은 이유로 닫는다. 닫은 개수를
   * 돌려준다. 기다리는 것이 없어도 열린 확인 요청은 닫는다(이미 닫혔으면 아무것도 하지 않음).
   */
  public int closeWaiting(CorrectionTargetKind kind, UUID targetId, ClosedReason reason) {
    List<CorrectionRequest> waiting =
        corrections.findByTargetKeyAndStatus(kind.targetKey(targetId), CorrectionStatus.WAITING);
    waiting.forEach(row -> row.close(reason, requestContext.at()));
    waits.close(
        RequestTarget.of(kind.targetKind(), targetId), RequestKind.CORRECTION_CONFIRM, reason);
    return waiting.size();
  }

  /**
   * 호실의 확인 전 고친 내용(기다림·미확인)을 모두 닫는다(정산 완료, 06 SET-5f). 고친 내용 확인 요청은 같은 트랜잭션의 {@code
   * WaitService.closeAll}이 함께 닫는다. 닫은 개수를 돌려준다.
   */
  public int closeAllInRoom(UUID roomRecordId, ClosedReason reason) {
    List<CorrectionRequest> open =
        corrections.findByRoomRecordIdAndStatusIn(
            roomRecordId, EnumSet.of(CorrectionStatus.WAITING, CorrectionStatus.UNCONFIRMED));
    open.forEach(row -> row.close(reason, requestContext.at()));
    return open.size();
  }
}
