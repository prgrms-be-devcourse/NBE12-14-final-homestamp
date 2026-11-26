package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.room.rule.PartyStatus;
import java.util.UUID;

/**
 * 받은 초대에 답한 결과(06 4-3 {@code connect}·{@code reject}).
 *
 * @param roomRecordId 연결했으면 이제 내 호실 기록, 거절했으면 없음(당사자가 아니라 호실을 알려 주지 않는다, 06 4-3 {@code
 *     invitations})
 * @param partyId 답한 자리
 * @param status 그 자리의 지금 상태. 같은 키로 다시 온 요청이면 그사이 바뀐 상태일 수 있다
 */
public record InvitationAnswered(UUID roomRecordId, UUID partyId, PartyStatus status) {

  /**
   * 서비스 결과.
   *
   * @param answered 응답 본문
   * @param replayed 같은 키로 다시 온 요청이라 새로 남긴 행동이 없는지(06 4-0)
   */
  public record Result(InvitationAnswered answered, boolean replayed) {}
}
