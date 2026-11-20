package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.RequestKind;
import java.util.Set;
import java.util.UUID;

/**
 * 응답 요청과 기다림(03 1-4, 09 3장 "기다림·통지"). 도메인은 원인 행동의 {@code StateStore.save} 안에서 부른다. 그래서 pipeline이 연
 * 트랜잭션과 호실 잠금 안에서 돈다. 문자는 커밋 뒤에 나간다.
 *
 * <p>요청은 <b>대상 + 종류</b>로 찾는다. 한 수리 건에 하자 확인·종료 확인이 따로 있고, 요청 ID를 도메인이 따로 들고 다니지 않게 하려고다(#86).
 */
public interface WaitService {

  /**
   * 확인 요청을 연다. 같은 대상·종류에 열린 요청이 있으면 새로 만들지 않고 그것을 돌려준다(기록당 확인 요청은 하나).
   *
   * <p><b>제안의 역제안</b>(상대의 안이 기다리는 중에 낸 안, 05 {@code proposal} ③): 이전 안의 요청은 답함, 다른 안에 새 요청이다. 그래서
   * 역제안을 남기는 {@code save}는 먼저 {@link #answered}(안건, {@code PROPOSAL}, 역제안한 측)로 이전 요청을 닫고 이 메서드를
   * 부른다. 안건마다 기다리는 요청이 하나라서({@code ux_proposal_waiting}) 이 순서여야 한다. 상대 쪽 제안 요청이 열린 채로 부르면 {@code
   * IllegalStateException}(그대로 두면 이전 요청이 남아 기다림이 끝나지 않음). 같은 쪽이 자기 안을 바꾸는 것(②)은 {@link #restart}다.
   */
  RequestHandle open(OpenRequestCommand command);

  /**
   * 상대가 답했다(맞아요·보류·이견, 미확인 뒤 늦은 답과 보류·이견에서 [맞아요]로 바꾸기 포함, #86). 그 대상·종류의 열린 요청(기다림·미확인)이 있으면 "답함"으로
   * 닫고 대기 재통지를 취소한다.
   *
   * <p>열린 요청이 없거나 이미 닫혔으면 아무것도 하지 않는다. 닫힌 요청을 다시 열지 않는다(거주 종료로 닫은 하자 확인처럼, 03 K). 요청을 보낸 측이 부르면 답이
   * 아니므로 아무것도 하지 않는다.
   */
  void answered(RequestTarget target, RequestKind kind, PartySide answeredBySide);

  /**
   * 보충·다시 요청([보충하고 다시 확인 요청]·[사진 더해서 다시 요청], 03 "응답과 그 뒤"). 7일 제한·근거 필수는 도메인 규칙({@code
   * AnswerSupport})이 먼저 본다. [상황이 바뀌었어요]는 진행 상황마다 새 상황 확인이라 {@link #open}이다.
   *
   * <p>요청 종류를 보고 기한을 정한다(03 1-4, #86):
   *
   * <ul>
   *   <li>요청이 없거나 닫혀 있으면: 새로 열고 기다림을 처음부터 센다(요청 없이 먼저 답한 기록 포함)
   *   <li>열려 있고 혼자 진행이 열리는 요청이면(하자·종료·점검 확인·정산 끝내기·거주 종료): 묶기만 하고 기한은 그대로다("열리는 요청의 기한은 보낸 뒤 고정").
   *       점검처럼 항목이 여럿이면 한 항목 보충이 다른 항목의 혼자 잠금을 늦추지 않는다
   *   <li>열려 있고 제안이면(낸 쪽이 자기 안을 바꾸기, 05 {@code proposal} ②): 같은 요청에 묶고 미확인이었으면 기다림으로 되돌려 바꾼 시각부터 다시
   *       센다(03 E). 상대 쪽 제안 요청이 열려 있으면 바꾸기가 아니라 역제안이라 {@code IllegalStateException}({@link #open}
   *       참고)
   *   <li>열려 있고 그 밖의 요청이면(고친 내용 확인 등): 묶고 마지막으로 더한 시각부터 다시 센다(03 1-4 "기한"). 고친 내용 확인이 미확인이 된 뒤 다시
   *       고치는 경우(03 1-4 "미확인 뒤 다시 고침")는 확인 중이다(#88)
   * </ul>
   *
   * <p>그 단계에서 보낼 수 없는 종류면 열지 않고 {@code IllegalStateException}을 던진다(화면 버그). 도메인은 {@link #canOpen}으로
   * 버튼과 규칙을 먼저 막는다.
   */
  RequestHandle restart(OpenRequestCommand command);

  /**
   * 한 대상·종류의 열린 요청을 답 없이 닫는다(금액이 바뀌어 제안이 닫힘, 정산이 바뀌어 끝내기가 닫힘, 처리 완료로 고친 내용 확인이 닫힘 등). 이미 닫혔으면 아무것도
   * 하지 않는다.
   *
   * <p>점검 확인은 점검 × 요청한 측마다 요청이 있어(03 H) 그 점검의 두 방향을 모두 닫는다. 대상 하나를 닫는 경우(제안·정산 끝내기·고친 내용)에는 방향이
   * 하나뿐이다.
   *
   * <p>호실 전체에 걸리는 닫기(공유 중단·정산 완료·거주 종료·탈퇴)는 {@link #closeAll}을 쓴다. 탈퇴는 그 호실의 열린 요청을 양방향 모두 {@code
   * COUNTERPART_WITHDRAWN}으로 닫는다(03 L, decisions 2026-11-20). 이 메서드로 처리하지 않는다.
   */
  void close(RequestTarget target, RequestKind kind, ClosedReason reason);

  /** 호실의 열린 요청 중 이 종류들을 모두 닫고 닫은 개수를 돌려준다(예: 거주 종료로 하자 확인·진행 상황 묻기·상황 변화 확인을 닫음, 03 K). */
  int closeAll(UUID roomRecordId, ClosedReason reason, Set<RequestKind> kinds);

  /**
   * 지금 호실 단계에서 이 종류의 요청을 열 수 있나(03 K "거주 종료 뒤", decisions 2026-11-18 다시 공유 규칙). 도메인 {@code load}가
   * State에 담아 {@code AnswerSupport}의 {@code canOpenRequest}·화면 버튼에 쓴다(#86). 판단은 {@code
   * RequestOpening}이고 호실 단계는 {@code CounterpartReader}가 돌려준다. 재통지·미확인 작업도 같은 판단을 쓴다.
   */
  boolean canOpen(UUID roomRecordId, RequestKind kind);

  /** 상대 응답 없이 요청한 측이 혼자 진행할 수 있나와 그 이유(03 1-4 ①~⑥). */
  SoloCheck canGoSolo(SoloQuery query);
}
