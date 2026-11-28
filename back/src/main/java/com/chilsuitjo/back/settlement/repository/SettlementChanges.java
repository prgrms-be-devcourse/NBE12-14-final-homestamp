package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.settlement.entity.Settlement;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산이 바뀌는 행동(항목·송금·보증금, 누가 했든)이 그 {@code save} 안에서 부른다(03 1-4 "정산 끝내기", 05 2-7). 호실을 잠근 뒤라 정산 행의
 * 번호를 올려도 다른 요청과 겹치지 않는다.
 *
 * <p><b>현재 구현 범위의 한계</b>: 기다리는 끝내기 요청을 "정산 내용이 바뀌어 닫혔어요"로 닫는 일(SET-5b)은 끝내기 기능(#441)에서 여기에 더한다.
 */
@Component
@RequiredArgsConstructor
public class SettlementChanges {

  private final SettlementRepository settlements;

  /** 정산 모습 번호를 올린다(불변식 19). */
  public void changed(UUID settlementId) {
    Settlement row =
        settlements
            .findById(settlementId)
            .orElseThrow(() -> new IllegalStateException("송금이 가리키는 정산이 없어요: " + settlementId));
    row.changed();
  }
}
