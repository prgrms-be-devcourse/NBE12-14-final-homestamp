package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionFollowUps;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.settlement.entity.Settlement;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산이 바뀌는 행동(항목·송금·보증금·고치기, 누가 했든)이 그 {@code save} 안에서 부른다(03 1-4 "정산 끝내기", 05 2-7). 호실을 잠근 뒤라 정산
 * 행의 번호를 올려도 다른 요청과 겹치지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SettlementChanges {

  private final SettlementRepository settlements;
  private final SettlementStateStore store;
  private final WaitService waits;
  private final ActionFollowUps followUps;

  /**
   * 정산 모습 번호를 올리고(불변식 19), 기다리는 끝내기 요청이 있으면 "정산 내용이 바뀌어 닫혔어요"({@code SETTLEMENT_CHANGED})로 닫는다(06
   * SET-5b). 닫으면 서버 행동 {@code CLOSE_FINISH_REQUEST}를 같은 요청의 딸린 행동으로 남긴다(09 3-1 {@code
   * ActionFollowUps}). 이렇게 닫힌 요청은 혼자 끝내기를 열지 않고, 다시 보내기는 7일 제한이 없다.
   */
  public void changed(UUID settlementId) {
    Settlement row =
        settlements
            .findById(settlementId)
            .orElseThrow(() -> new IllegalStateException("가리키는 정산이 없어요: " + settlementId));
    row.changed();
    RequestTarget target = SettlementStateStore.target(settlementId);
    if (store.openRequest(target).isPresent()) {
      waits.close(target, RequestKind.SETTLE_FINISH, ClosedReason.SETTLEMENT_CHANGED);
      followUps.add(
          Actor.server(),
          ActionType.CLOSE_FINISH_REQUEST,
          ActionTarget.settlement(settlementId),
          Map.of());
    }
  }
}
