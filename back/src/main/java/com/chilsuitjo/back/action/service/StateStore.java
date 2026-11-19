package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.SavedState;

/**
 * 각 도메인이 채우는 빈칸: 내 상태 행을 읽고 쓰는 법. 구현 {@code XxxStateStore}는 도메인의 {@code repository/}에 둔다(09 2장).
 * 규칙은 검사하지 않는다(규칙은 {@code Transitions}에만). 내 엔티티는 내 Repository로 읽고 쓰고, 다른 도메인 값은 그 주인의 공개 서비스나 포트로
 * 읽기만 한다(예: 정산·수리의 {@link #load}가 {@code CounterpartReader}로 자리 상태를 읽음, 09 3장 "기다림·통지"). {@link
 * #load}는 호실을 잠근 뒤에 불리므로 이때 읽은 값도 최신이다(09 3-1 "계약").
 *
 * <p>링크·응답 요청·문자처럼 행동이 아닌 딸린 일은 {@link #save} 안에서 주인 서비스를 불러 같은 트랜잭션에 넣는다. 서버 기록이 하나 더 필요하면 {@link
 * ActionFollowUps#add}를 쓴다(pipeline을 다시 부르지 않는다).
 *
 * @param <S> 그 도메인의 상태 값({@code Transitions}와 같은 타입)
 */
public interface StateStore<S> {

  /** 지금 상태. 만드는 행동(대상이 아직 없음)이면 {@code Transitions.initial()}과 같은 빈 상태를 돌려준다. */
  S load(ActionCommand command);

  /** {@code apply}가 정한 다음 상태를 쓴다. 새로 만든 대상·판이 있으면 {@link SavedState}로 알려 준다. */
  SavedState save(ActionCommand command, S next);
}
