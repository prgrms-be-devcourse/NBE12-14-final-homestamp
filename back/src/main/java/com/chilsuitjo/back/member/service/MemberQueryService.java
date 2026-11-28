package com.chilsuitjo.back.member.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 다른 도메인이 회원 쪽 값을 읽는 창구. 실명은 내보내지 않는다. */
public interface MemberQueryService {

  /**
   * 가린 이름(정서윤 → 정*윤, 06 ROOM-2c). 탈퇴한 회원이면 비어 있다(이름을 지웠으므로, 부르는 쪽이 "탈퇴한 임대인/임차인"으로 보인다). 없는 회원이면
   * {@code NOT_FOUND}를 던진다.
   */
  Optional<String> maskedName(UUID memberId);

  /**
   * 여러 회원의 가린 이름을 한 번에 읽는다(타임라인·확인 카드 목록처럼 행마다 이름을 보일 때). 값은 {@link #maskedName}과 같다: 탈퇴한 회원은 비어
   * 있고, 하나라도 없는 회원이 있으면 {@code NOT_FOUND}를 던진다. 같은 ID가 여러 번 와도 한 번만 담고, 빈 목록이면 빈 맵을 돌려준다.
   */
  Map<UUID, Optional<String>> maskedNames(Collection<UUID> memberIds);

  /**
   * 회원이 지금 인증해 둔 번호(숫자만, 05 0장 정규화). 초대할 때 내 번호인지({@code SELF_INVITE}), 받은 초대와 연결할 때 초대 번호와 같은지 보는
   * 데 쓴다(06 ROOM-4a·5a·5b, #100·#163). 다른 계정이 같은 번호를 인증해 인증이 풀렸거나(06 ACC-3a) 탈퇴했으면 비어 있다. 없는 회원이면
   * {@code NOT_FOUND}를 던진다.
   *
   * <p>번호는 로그·예외 문구·응답에 남기지 않는다(04 "개인정보 로그"). 비교나 조회 조건에만 쓴다.
   */
  Optional<String> verifiedPhone(UUID memberId);

  /**
   * 회원 행을 공유 잠금하고({@code FOR SHARE}, 트랜잭션이 끝날 때 풀림) 활동 중인지 본다. 탈퇴가 진행 중이면(회원 행 쓰기 잠금) 그 탈퇴가 끝날 때까지
   * 기다렸다가 거짓이다. 연결(06 ROOM-5a)이 호실을 잠근 뒤 부른다: 탈퇴가 자리 목록을 읽은 뒤 같은 회원의 연결이 끼어들어 탈퇴한 회원의 연결된 자리가 남는 일을
   * 막는다(#424). 잠금 순서는 호실 → 회원 행이라 탈퇴(호실 → 회원 행)와 같다. 부르는 쪽 트랜잭션 안에서만 부른다.
   */
  boolean holdActive(UUID memberId);
}
