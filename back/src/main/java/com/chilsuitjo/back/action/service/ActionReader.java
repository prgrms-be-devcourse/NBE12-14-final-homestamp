package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import java.util.List;
import java.util.UUID;

/** 행동 기록 읽기. 보이는 범위(불변식 18, 공유를 그만둔 뒤 상대 행동 빼기) 거르기는 부르는 쪽이 한다(action은 party를 모른다). */
public interface ActionReader {

  /** 한 대상의 행동, {@code room_seq} 순. 경과 보기(REP-11)와 재계산에 쓴다. */
  List<ActionEntry> findByTarget(ActionTarget target);

  /** 한 호실의 행동 전체, {@code room_seq} 순. */
  List<ActionEntry> findByRoom(UUID roomRecordId);
}
