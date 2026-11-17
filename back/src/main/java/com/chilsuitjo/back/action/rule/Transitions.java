package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;

/**
 * 상태 전이 틀. 각 도메인의 {@code *Transitions}가 구현한다(04 4-0 "순수 함수 apply(지금 상태, 행동) → 다음 상태 하나").
 *
 * <p>지킬 것: Repository·Clock·다른 서비스를 부르지 않는다. 같은 입력이면 늘 같은 결과. 규칙 위반이면 {@code BusinessException}을
 * 던진다. 기록 단위 규칙({@code NOT_COUNTERPART_SIDE}, {@code ACK_FINAL} …)만 보고 호실 단위 규칙(정산 완료·합류 전 기록·공유
 * 중단)은 보지 않는다. 그건 pipeline이 잠근 뒤 막는다(09 3-1).
 *
 * <p>입력은 {@code action.payloadAs(자기 record)}로 읽는다.
 *
 * @param <S> 그 도메인의 상태 값(불변 record 권장)
 */
public interface Transitions<S> {

  /** 아무 행동도 없을 때의 상태(재계산의 시작점, 만드는 행동의 지금 상태). */
  S initial();

  S apply(S now, ActionEntry action);
}
