package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 행동 기록 읽기. 보이는 범위(불변식 18, 공유를 그만둔 뒤 상대 행동 빼기) 거르기는 부르는 쪽이 한다(action은 party를 모른다).
 *
 * <p>진짜 구현({@code JpaActionWriter})은 {@code @Transactional(MANDATORY)}라 트랜잭션 안에서만 부를 수 있다. 조회 서비스는
 * 읽기 트랜잭션({@code @Transactional(readOnly = true)}) 안에서 부른다.
 */
public interface ActionReader {

  /**
   * 한 대상의 행동, {@code room_seq} 순. 경과 보기(REP-11)와 재계산에 쓴다. {@code ActionTarget.roomRecord(id)}면 그
   * 호실에서 대상 칸 없이 남은 행동(호실 기록 자체가 대상: 이사함, 정산 끝내기 …)이다.
   */
  List<ActionEntry> findByTarget(ActionTarget target);

  /** 한 호실의 행동 전체, {@code room_seq} 순. */
  List<ActionEntry> findByRoom(UUID roomRecordId);

  /**
   * 이 호실에서 이 대상들 중 하나를 가리키는 행동, {@code room_seq} 순. 목록 한 쪽처럼 여러 대상의 행동이 필요할 때 대상마다 부르지 않고 한 번에
   * 읽는다(#289 리뷰). 호실 자체 대상({@code ActionTarget.roomRecord})은 받지 않는다({@link #findByTarget}). 대상이 없으면
   * 빈 목록.
   */
  List<ActionEntry> findByTargets(UUID roomRecordId, Collection<ActionTarget> targets);
}
