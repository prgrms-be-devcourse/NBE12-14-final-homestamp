package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.Objects;
import java.util.UUID;

/**
 * 누가 했나. 이름·번호는 넣지 않는다(불변식 1). {@code key}는 05 {@code action.actor_key}: 회원 ID, {@code server},
 * {@code link:<ID>}, {@code ops:<담당>}.
 *
 * <p>회원의 측은 부르는 쪽이 넣지 않는다. pipeline이 잠근 뒤 읽은 {@code RoomGate}에서 채운다(09 3-1 "잠금 뒤 판단").
 *
 * @param memberId 회원이 아니면 null
 * @param side 서버·운영 행동이면 null. 회원은 pipeline이 채우고, 링크는 link가 받은 자리의 측을 넣는다
 */
public record Actor(UUID memberId, PartySide side, String key) {

  public Actor {
    Objects.requireNonNull(key, "key");
  }

  public static Actor member(UUID memberId) {
    return new Actor(Objects.requireNonNull(memberId, "memberId"), null, memberId.toString());
  }

  /** 가입 전 사람이 링크로 한 행동(열람, 문자 그만 받기). 측은 link가 링크를 받은 자리에서 넣는다. */
  public static Actor link(UUID linkId, PartySide side) {
    return new Actor(null, side, "link:" + Objects.requireNonNull(linkId, "linkId"));
  }

  /** 작업자의 행동(재통지, 미확인, 자동 마감, 정산이 바뀌어 끝내기 요청 닫기 …). */
  public static Actor server() {
    return new Actor(null, null, "server");
  }

  /** 운영 명령(작업 대장과 짝). */
  public static Actor ops(String operator) {
    return new Actor(null, null, "ops:" + Objects.requireNonNull(operator, "operator"));
  }

  public boolean isMember() {
    return memberId != null;
  }

  /** 서버·운영 행동은 호실 단위 거절을 건너뛴다(09 3-1 순서 3). */
  public boolean isSystem() {
    return "server".equals(key) || key.startsWith("ops:");
  }

  public Actor withSide(PartySide newSide) {
    return new Actor(memberId, newSide, key);
  }
}
