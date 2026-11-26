package com.chilsuitjo.back.notification.service;

import java.util.Collection;
import java.util.UUID;

/**
 * 아직 보내지 않은 문자를 취소한다(#100 U2). room이 번호를 고쳐 다시 초대할 때 부르고 notification이 구현한다.
 *
 * <p>room은 번호를 고친 저장에서 링크를 무효화한 직후 같은 트랜잭션으로 호출한다. 링크 ID 목록은 링크 포트가 반환한 값을 그대로 넘긴다.
 */
public interface SmsCancellation {

  /**
   * 이 호실에서 넘겨받은 링크를 가리키는 문자 통지 가운데, 문자 줄이 아직 {@code PENDING}인 것을 취소한다. 문자 줄은 {@code CANCELLED}, 통지는
   * {@code REQUESTED → CANCELLED}로 함께 바뀐다.
   *
   * <ul>
   *   <li>부르는 곳: pipeline {@code save} 안, 호실 잠금 뒤. 번호 고치기라면 링크 무효({@code invalidateInvites})와 새 링크
   *       발급({@code issueInvite}) 사이. {@code save} 밖이면 {@link IllegalStateException}, 트랜잭션 밖이면
   *       {@code IllegalTransactionStateException}
   *   <li>링크 ID에는 이미 무효였던 링크도 넣어도 된다. 그 링크를 가리키는 보내지 않은 문자도 취소한다
   *   <li>그대로 두는 것: 링크 없는 통지, 다른 호실·목록 밖 링크, 서비스 안 알림, 문자 줄이 {@code SENDING}·{@code DONE}·{@code
   *       FAILED}·{@code CANCELLED}인 것. {@code SENDING}은 작업자가 이미 가져간 줄이라 이 메서드로 막지 못한다
   *   <li>지금 코드로 생길 수 없는 조합(문자 줄은 {@code PENDING}인데 통지가 {@code REQUESTED}가 아님)이면 {@link
   *       IllegalStateException}으로 부른 행동 전체를 되돌린다
   * </ul>
   *
   * @return 이번 호출에서 {@code CANCELLED}로 바꾼 문자 줄 수. 빈 목록이면 DB를 건드리지 않고 0, 같은 트랜잭션에서 다시 불러도 0
   */
  int cancelPendingSms(UUID roomRecordId, Collection<UUID> linkIds);
}
