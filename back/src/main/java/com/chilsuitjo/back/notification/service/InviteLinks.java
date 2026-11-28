package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import java.util.List;
import java.util.UUID;

/**
 * 거주 중 초대 링크({@code INVITE})를 만들고 닫는 포트. notification이 두고 link(D)가 구현한다(#163, 09 3장 "기다림·통지").
 * {@code room → link}를 더하면 {@code link → room}과 고리가 생겨서, {@link CounterpartReader}처럼 아래층에 둔다.
 *
 * <p>모든 메서드는 pipeline이 호실을 잠근 뒤 {@code save} 안에서, 같은 트랜잭션으로 부른다. 트랜잭션이 없거나 {@code save} 밖(행동 시각 창구가
 * 닫혀 있음)이면 개발 오류다. 링크를 만든 시각·무효 시각은 그 행동의 시각이다(decisions 2026-11-19). 구현도 호실 → 링크 순서로 잠근다(09 3-1 잠금
 * 순서). 초대 저장이 실패하면 링크 행도 함께 되돌아간다.
 *
 * <p>검증 나누기: 누가 언제 초대할 수 있는지(만든 측, 자리 상태, 거주 중, 자기 번호, 공유 동의, 수신 거부)는 부르는 쪽(room)이 본다. 이 포트는 링크의
 * 모양만 본다(기록은 하나, 대상 종류, 자리·기록이 그 호실의 것인지). 그 기록을 공유해도 되는지는 보지 않는다(부르는 쪽 규칙).
 *
 * <p>토큰과 주소는 돌려주지 않는다. 부르는 쪽·행동 기록·로그 어디에도 남지 않게 링크 ID만 오간다(03 C, 06 LINK-2b). 문자에 넣을 주소는 보낼 때 작업자가
 * 따로 받는다(#163 ③, 아직 없음).
 */
public interface InviteLinks {

  /** 초대 링크가 이미 무효인지 확인한다. 초대가 아닌 링크 ID나 없는 ID는 {@code false}. */
  boolean isInvalidatedInvite(UUID linkId);

  /**
   * 번호를 고쳐 다시 초대해 무효가 된({@code REINVITED}) 초대 링크인지. 그 링크를 담은 문자는 고치기 전 번호로 갔으므로, 업체가 전달됐다고 해도 지금
   * 상대가 받았다는 증거가 아니다(#370 T-2, 03 1-1 "타이머 시작점"). 다른 이유로 무효인 링크(연결 {@code CONNECTED} 등), 유효한 링크,
   * 초대가 아닌 링크, 없는 ID는 {@code false}다. 무효가 된 처음 이유는 바뀌지 않는다(05 불변식 7).
   *
   * <p>호실을 잠근 뒤 같은 트랜잭션에서 부른다. 번호 고치기의 무효화도 호실을 잠그고 하므로 둘은 한 줄로 서고, 잠근 뒤 읽으면 먼저 끝난 무효화가 보인다.
   */
  boolean isReinvited(UUID linkId);

  /**
   * 초대 링크를 만들고 ID를 돌려준다. 같은 호실·같은 받는 자리·같은 기록에 아직 유효한 초대 링크가 있으면 새로 만들지 않고 그 ID를 돌려준다(같은 번호로 다시
   * 보내기는 같은 링크, #163).
   *
   * <p><b>다시 쓰는 기준</b>은 호실·받는 자리·기록(없으면 호실 초대 화면)뿐이다. 받는 번호와 발급자({@code issuedByMemberId})는 기준이
   * 아니다. 다시 쓰면 그 링크 행을 바꾸지 않아 처음 발급자와 만든 시각이 그대로 남는다. 지금 초대는 호실을 만든 측만 하고 한 측의 자리는 하나라서(03 B "상대
   * 초대하기", V1 {@code uq_party_room_side}) 같은 자리에 초대하는 회원은 늘 같다. 발급자가 바뀌는 흐름(당사자 교체 등)은 지원하지 않으며,
   * 생기면 그때 이 계약을 다시 정한다.
   *
   * <p><b>번호를 고쳐 다시 초대할 때</b>: 받는 번호는 다시 쓰는 기준이 아니어서, 옛 링크를 닫지 않고 부르면 옛 번호로 보낸 링크가 그대로 돌아온다(옛 번호를
   * 받은 사람이 계속 열 수 있음). 그래서 부르는 쪽은 같은 트랜잭션 안에서 이 순서를 지킨다: ① 다시 초대할 수 있는지와 번호가 바뀌었는지 확인(room) ②
   * {@link #invalidateInvites(UUID, UUID, LinkInvalidReason)}로 그 자리 링크를 {@code REINVITED} 무효로 하고 링크
   * ID 목록을 받음 ③ 그 ID가 담긴 보내지 않은 문자를 취소(notification, #100 U2, 아직 없음) ④ 이 메서드로 새 링크 ⑤ 통지. 이 포트는 번호를
   * 모르므로 이 순서를 검사하지 않는다. 순서를 지키는지는 room의 번호 고치기 구현과 그 테스트에서 확인해야 한다(아직 없음, #100·#163).
   *
   * <p>누가 언제 초대할 수 있는지는 부르는 쪽이 이미 확인했다고 보고 다시 보지 않는다. 링크를 돌려줬다고 초대가 허락됐다는 뜻은 아니다.
   *
   * <p><b>기록이 그 호실에 없을 때</b>: {@code record}가 가리키는 수리 건·점검이 없거나 다른 호실의 것이면 {@link
   * com.chilsuitjo.back.global.error.BusinessException}({@code NOT_FOUND})이다(#163). 없는 기록과 다른 호실의
   * 기록은 구분하지 않는다. 이 예외가 나면 그 트랜잭션은 되돌리기만 할 수 있으니 잡고 계속하지 말고 그대로 올린다. 그러면 같은 트랜잭션의 행동·자리·링크·통지가 함께
   * 되돌아간다. 자리와 기록이 둘 다 틀리면 어느 예외가 나올지는 정하지 않는다.
   *
   * @param roomRecordId 링크가 속한 호실(잠금에 씀)
   * @param recipientPartyId 받는 자리({@code party}). 번호는 넘기지 않고 보낼 때 자리로 읽는다. 부르는 쪽이 호실을 잠근 뒤 정하는 값이라,
   *     그 호실의 자리가 아니면 개발 오류로 저장이 실패한다({@code DataIntegrityViolationException}, DB 외래 키)
   * @param record 공유할 기록. {@code REPAIR_CASE}·{@code INSPECTION}만 받고(그 밖의 종류는 {@code
   *     IllegalArgumentException}이라 부르는 쪽이 입력 검증으로 먼저 막는다), {@code null}이면 호실 초대 화면 링크다. 없거나 그 호실의
   *     기록이 아니면 {@code NOT_FOUND}
   * @param issuedByMemberId 초대한 회원(pipeline {@code Actor}의 회원). 새로 만들 때만 저장하고 "발급한 사람 탈퇴"({@code
   *     ISSUER_WITHDRAWN}) 무효에 쓴다. 탈퇴 때 링크를 무효로 하는 일은 탈퇴(KEEP-4)에서 한다(아직 없음)
   */
  UUID issueInvite(
      UUID roomRecordId, UUID recipientPartyId, RequestTarget record, UUID issuedByMemberId);

  /**
   * 그 호실의 유효한 초대 링크를 모두 무효로 하고 이번에 무효로 한 개수를 돌려준다(거주 종료 {@code RESIDENCE_ENDED} 등). 이미 무효인 링크는
   * 건너뛰고, 처음 무효가 된 시각과 이유는 바꾸지 않는다(05 불변식 7). 정산 초대 링크({@code SETTLEMENT_INVITE})는 건드리지 않는다.
   */
  int invalidateInvites(UUID roomRecordId, LinkInvalidReason reason);

  /**
   * 그 호실에서 한 자리에 보낸 초대 링크를 모두 무효로 하고, 그 자리의 초대 링크 ID를 <b>전부</b> 돌려준다(번호 고쳐 다시 초대 {@code REINVITED},
   * 연결 {@code CONNECTED}).
   *
   * <p>돌려주는 목록에는 이번에 무효로 한 링크와 <b>이미 무효였던 링크</b>가 함께 들어간다. 공유 그만두기처럼 다른 이유로 먼저 닫힌 링크가 담긴 보내지 않은 문자도
   * 취소할 수 있게 하려는 것이다(#100 기본안 "기존 링크 ID 집합", #236에서 A·D 합의).
   *
   * <ul>
   *   <li>부른 뒤 목록의 링크는 모두 무효다. 이미 무효인 링크는 다시 저장하지 않아 처음 이유·시각이 그대로다(05 불변식 7)
   *   <li>그 호실·그 자리의 {@code INVITE}만 넣는다. 다른 자리·다른 호실의 링크와 정산 초대 링크({@code SETTLEMENT_INVITE})는 넣지
   *       않는다
   *   <li>링크가 없으면 빈 목록이다. 같은 ID는 두 번 들어가지 않는다. 같은 트랜잭션에서 다시 불러도 같은 집합이다
   *   <li>목록의 순서는 계약이 아니다(집합으로 쓴다). 바꿀 수 없는 목록이다
   * </ul>
   *
   * <p>부르는 쪽(room)은 이 목록을 같은 트랜잭션의 미발송 취소에 그대로 넘긴다. 취소는 그 호실·자리의 통지를 통째로 지우지 않고 이 링크 ID가 담긴 보내지 않은
   * 문자만 다루며, 빈 목록이면 아무것도 하지 않는다(#100 U2, 아직 없음). 번호를 고쳐 다시 초대할 때는 새 링크를 만들기 전에 이것을 먼저 부른다({@link
   * #issueInvite} 설명의 순서 ②).
   */
  List<UUID> invalidateInvites(UUID roomRecordId, UUID partyId, LinkInvalidReason reason);
}
