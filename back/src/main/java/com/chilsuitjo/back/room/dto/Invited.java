package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.room.rule.PartyStatus;
import java.util.UUID;

/**
 * 초대 결과(06 4-3 {@code POST /api/rooms/{id}/invite}).
 *
 * @param partyId 초대한 상대 자리
 * @param status 그 자리의 지금 상태. 처음 초대면 {@code INVITED}. 같은 키로 다시 온 요청이면 그사이 바뀐 상태일 수 있다(연결됨 등)
 */
public record Invited(UUID partyId, PartyStatus status) {

  /**
   * 서비스 결과.
   *
   * @param invited 응답 본문
   * @param replayed 같은 키로 다시 온 요청이라 새로 남긴 행동이 없는지(06 4-0)
   */
  public record Result(Invited invited, boolean replayed) {}
}
