package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;

/**
 * 상태 전이 틀. 각 도메인의 {@code *Transitions}가 구현한다(04 4-0 "순수 함수 apply(지금 상태, 행동) → 다음 상태 하나").
 *
 * <p>지킬 것: Repository·Clock·다른 서비스를 부르지 않는다. 같은 입력이면 늘 같은 결과. 규칙 위반이면 {@code BusinessException}을
 * 던진다. 기록 단위 규칙({@code NOT_COUNTERPART_SIDE}, {@code ACK_FINAL} …)을 본다. pipeline이 잠근 뒤 막는 공통
 * 거절({@code SETTLED}·{@code ROOM_ENDED}·{@code JOINED_RECORD}·{@code SHARING_STOPPED})은 다시 보지 않는다.
 * 같은 호실 사실이라도 행동마다 결과가 다른 판단(예: 상대 공유 중단에 따른 정산 끝내기 막기, 그만둔 동안 사진 대상 제한)은 {@code StateStore.load}가
 * 상태에 담아 준 값으로 여기서 본다(09 3-1 "계약").
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
