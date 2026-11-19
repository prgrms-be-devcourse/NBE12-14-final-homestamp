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

  /** 확인 요청을 연다. 같은 대상·종류에 열린 요청이 있으면 새로 만들지 않고 그것을 돌려준다(기록당 확인 요청은 하나). */
  RequestHandle open(OpenRequestCommand command);

  /**
   * 상대가 답했다(맞아요·보류·이견, 늦은 답·보류↔이견 바꾸기 포함, #86). 그 대상·종류의 열린 요청(기다림·미확인)이 있으면 "답함"으로 닫고 대기 재통지를
   * 취소한다.
   *
   * <p>열린 요청이 없거나 이미 닫혔으면 아무것도 하지 않는다. 닫힌 요청을 다시 열지 않는다(거주 종료로 닫은 하자 확인처럼, 03 K). 요청을 보낸 측이 부르면 답이
   * 아니므로 아무것도 하지 않는다.
   */
  void answered(RequestTarget target, RequestKind kind, PartySide answeredBySide);

  /**
   * 보충·다시 요청([보충하고 다시 확인 요청]·[사진 더해서 다시 요청]·[상황이 바뀌었어요], 03 "응답과 그 뒤"). 같은 대상·종류의 요청을 다시 열고 새 기다림을
   * 시작한다. 없으면 새로 연다. 7일 제한·근거 필수는 도메인 규칙({@code AnswerSupport})이 먼저 본다.
   *
   * <p>그 단계에서 보낼 수 없는 종류면 열지 않고 {@code IllegalStateException}을 던진다(화면 버그). 도메인은 {@link #canOpen}으로
   * 버튼과 규칙을 먼저 막는다.
   */
  RequestHandle restart(OpenRequestCommand command);

  /** 한 요청을 답 없이 닫는다(공유 중단·상대 탈퇴·정산이 바뀜 등). 이미 닫혔으면 아무것도 하지 않는다. */
  void close(RequestTarget target, RequestKind kind, ClosedReason reason);

  /** 호실의 열린 요청 중 이 종류들을 모두 닫고 닫은 개수를 돌려준다(예: 거주 종료로 하자 확인·진행 상황 묻기·상황 변화 확인을 닫음, 03 K). */
  int closeAll(UUID roomRecordId, ClosedReason reason, Set<RequestKind> kinds);

  /**
   * 지금 호실 단계에서 이 종류의 요청을 열 수 있나(03 K "거주 종료 뒤", decisions 2026-11-18 다시 공유 규칙). 도메인 {@code load}가
   * State에 담아 {@code AnswerSupport.canRerequest}·화면 버튼에 쓴다(#86).
   */
  boolean canOpen(UUID roomRecordId, RequestKind kind);

  /** 상대 응답 없이 요청한 측이 혼자 진행할 수 있나와 그 이유(03 1-4 ①~⑥). */
  SoloCheck canGoSolo(SoloQuery query);
}
