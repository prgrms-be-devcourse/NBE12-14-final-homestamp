package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import java.util.UUID;

/**
 * 거주 중 초대 링크({@code INVITE})를 만들고 닫는 포트. notification이 두고 link(D)가 구현한다(#163, 09 3장 "기다림·통지").
 * {@code room → link}를 더하면 {@code link → room}과 고리가 생겨서, {@link CounterpartReader}처럼 아래층에 둔다.
 *
 * <p>둘 다 pipeline이 호실을 잠근 뒤 {@code save} 안에서, 같은 트랜잭션으로 부른다(트랜잭션이 없으면 개발 오류). 구현도 호실 → 링크 순서로 잠근다
 * (09 3-1 잠금 순서). 초대 저장이 실패하면 링크 행도 함께 되돌아간다.
 *
 * <p>검증 나누기: 누가 언제 초대할 수 있는지(만든 측, 자리 상태, 거주 중, 자기 번호, 공유 동의, 수신 거부)는 부르는 쪽(room)이 본다. 이 포트는 링크의
 * 모양만 본다(기록은 하나, 대상 종류, 자리·기록이 그 호실의 것인지).
 *
 * <p>토큰과 주소는 돌려주지 않는다. 부르는 쪽·행동 기록·로그 어디에도 남지 않게 링크 ID만 오간다(03 C, 06 LINK-2b). 문자에 넣을 주소는 보낼 때 작업자가
 * 따로 받는다(#163 ③, 아직 없음).
 */
public interface InviteLinks {

  /**
   * 초대 링크를 만들고 ID를 돌려준다. 같은 호실·같은 받는 자리·같은 기록에 아직 유효한 초대 링크가 있으면 새로 만들지 않고 그 ID를 돌려준다(같은 번호로 다시
   * 보내기는 같은 링크, #163).
   *
   * @param roomRecordId 링크가 속한 호실(잠금에 씀)
   * @param recipientPartyId 받는 자리({@code party}). 번호는 넘기지 않고 보낼 때 자리로 읽는다. 그 호실의 자리가 아니면 저장이
   *     실패한다(DB 외래 키)
   * @param record 공유할 기록. {@code REPAIR_CASE}·{@code INSPECTION}만 받고, {@code null}이면 호실 초대 화면 링크다.
   *     그 호실의 기록이 아니면 저장이 실패한다(DB 외래 키)
   * @param issuedByMemberId 초대한 회원(pipeline {@code Actor}의 회원). "발급한 사람 탈퇴" 무효에 쓴다
   */
  UUID issueInvite(
      UUID roomRecordId, UUID recipientPartyId, RequestTarget record, UUID issuedByMemberId);

  /**
   * 그 호실의 유효한 초대 링크를 모두 무효로 하고 이번에 무효로 한 개수를 돌려준다(거주 종료 {@code RESIDENCE_ENDED} 등). 이미 무효인 링크는
   * 건너뛰고, 처음 무효가 된 시각과 이유는 바꾸지 않는다(05 불변식 7). 정산 초대 링크({@code SETTLEMENT_INVITE})는 건드리지 않는다.
   */
  int invalidateInvites(UUID roomRecordId, LinkInvalidReason reason);

  /**
   * 그 호실에서 한 자리에 보낸 유효한 초대 링크만 무효로 하고 개수를 돌려준다(번호 고쳐 다시 초대 {@code REINVITED}, 연결 {@code
   * CONNECTED}). 나머지는 {@link #invalidateInvites(UUID, LinkInvalidReason)}와 같다.
   */
  int invalidateInvites(UUID roomRecordId, UUID partyId, LinkInvalidReason reason);
}
