package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 응답하기(맞아요·보류·이견)와 다시 요청([보충하고 다시 확인 요청]·[사진 더해서 다시 요청])의 공통 규칙(#86, decisions 2026-11-17·11-19, 03
 * "응답과 그 뒤", 06 4-0). 수리 건 하자·점검 항목·지불·송금·고친 내용·원인 설명(응답만, 고치면 새 설명이라 다시 요청이 없음)이 각자의 {@code
 * XxxTransitions.apply} 안에서 부른다.
 *
 * <p>순수 함수다: Repository·{@code Clock}·호실 단계를 모른다. 지금 시각은 {@code apply}가 받은 {@code
 * entry.createdAt()}을, 간격({@code rerequest.interval})은 각 {@code Transitions}가 설정에서 받아 넘긴다.
 *
 * <p><b>도메인이 먼저 막는 것</b>(여기서 보지 않음): 상대가 아직 볼 수 없는 기록(작성 중 점검 등), 그 단계에서 할 수 없는 행동(거주 종료 뒤 열린 수리 건의
 * 종료 확인 등), 정산 끝내기(규칙이 다름, {@code AnswerResult}), 돈 안건 장치의 제안·동의({@code ProposalService}, {@code
 * OWN_PROPOSAL}). 정산 완료 뒤 막기는 pipeline({@code SETTLED})이 한다.
 *
 * <p>[상황이 바뀌었어요]는 보충과 조건이 달라(하자를 둘 다 확인한 뒤 누구든, 06 REP-6d) {@link #rerequest}를 쓰지 않고 {@link
 * #requireEvidence}·{@link #checkInterval}만 쓴다.
 */
public final class AnswerSupport {

  private AnswerSupport() {}

  /**
   * 응답하기. 검사 순서: 상대 측 → 판 번호 → 맞아요는 끝 → 보류·이견에서는 맞아요로만(#86 질문 1) → 보류·이견은 사유 필수.
   *
   * @param answerer 답하는 측. 기록한 측의 반대여야 한다
   * @param seenVersionNo 확인할 때 본 판 번호({@code RespondRequest.seenVersionNo})
   * @return 다음 응답 값
   */
  public static AckState answer(
      Answerable record, PartySide answerer, Answer answer, String reason, Integer seenVersionNo) {
    if (answer == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    if (!isCounterpart(record, answerer)) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
    if (record.versionNo() != null && !record.versionNo().equals(seenVersionNo)) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    if (record.state() == AckState.ACK) {
      throw new BusinessException(ErrorCode.ACK_FINAL);
    }
    if (isObjection(record.state()) && answer != Answer.ACK) {
      // 보류 ↔ 이견은 새 요청 없이 바꾸지 않는다. 다시 고르려면 기록한 측이 보충한다(03 G "응답은 요청마다 새로 고른다").
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (answer != Answer.ACK && (reason == null || reason.isBlank())) {
      throw new BusinessException(ErrorCode.REASON_REQUIRED);
    }
    return AckState.valueOf(answer.name());
  }

  /**
   * 화면에서 응답 버튼을 켤지({@code availableActions}, 06 4-0). {@link #answer}가 맞아요로 성공하는지와 같다. 보류·이견에서도
   * true다(→ 맞아요). 확인 카드(NOTI-7)도 이 값을 쓴다.
   */
  public static boolean canAnswer(Answerable record, PartySide side) {
    return isCounterpart(record, side) && record.state() != AckState.ACK;
  }

  /**
   * 다시 요청. 검사 순서: 기록한 측 → 보류·이견일 때만 → 지금 이 종류의 요청을 열 수 있나 → 근거 → 간격(#86). 근거를 간격보다 먼저 봐서, 서비스가
   * pipeline 전에 {@link #requireEvidence}를 먼저 불러도 도메인마다 오류 순서가 같다.
   *
   * @param hasEvidence 사진이나 설명이 하나 이상인지({@code SupplementRequest.hasEvidence()})
   * @param canOpenRequest 지금 호실 단계에서 이 종류의 응답 요청을 열 수 있는지({@code waitService.canOpen}, 03 K). 각 도메인
   *     {@code load}가 State에 담는다
   * @param now 이 행동의 시각({@code entry.createdAt()})
   * @return 다음 응답 값({@code PENDING}, 새로 고른다)과 저장할 다시 요청 시각
   */
  public static Rerequested rerequest(
      Answerable record,
      PartySide requester,
      boolean hasEvidence,
      boolean canOpenRequest,
      Instant now,
      Duration interval) {
    if (record.recordedBy() == null || record.recordedBy() != requester) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
    if (!isObjection(record.state()) || !canOpenRequest) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    requireEvidence(hasEvidence);
    checkInterval(record.lastRerequestAt(), now, interval);
    return new Rerequested(AckState.PENDING, now);
  }

  /** 화면에서 다시 요청 버튼을 켤지. 근거를 더했다고 보고 {@link #rerequest}가 성공하는지와 같다. */
  public static boolean canRerequest(
      Answerable record, PartySide side, boolean canOpenRequest, Instant now, Duration interval) {
    return record.recordedBy() != null
        && record.recordedBy() == side
        && isObjection(record.state())
        && canOpenRequest
        && !tooSoon(record.lastRerequestAt(), now, interval);
  }

  /** 다음에 다시 요청할 수 있는 시각. 화면의 "○월 ○일부터 다시 요청할 수 있어요"에 쓴다. 다시 요청한 적이 없으면 null(지금 바로 됨). */
  public static Instant nextRerequestAt(Answerable record, Duration interval) {
    return record.lastRerequestAt() == null ? null : record.lastRerequestAt().plus(interval);
  }

  /** 세 버튼이 함께 쓰는 검사: 사진이나 설명이 하나 이상(03 G "다시 확인 요청의 한도"). */
  public static void requireEvidence(boolean hasEvidence) {
    if (!hasEvidence) {
      throw new BusinessException(ErrorCode.EVIDENCE_REQUIRED);
    }
  }

  /** 세 버튼이 함께 쓰는 검사: 한 기록에 간격에 한 번. 마지막 시각이 없으면 통과. 간격이 딱 지난 때부터 된다. */
  public static void checkInterval(Instant last, Instant now, Duration interval) {
    if (tooSoon(last, now, interval)) {
      throw new BusinessException(ErrorCode.ASKED_RECENTLY);
    }
  }

  private static boolean tooSoon(Instant last, Instant now, Duration interval) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(interval, "interval");
    return last != null && now.isBefore(last.plus(interval));
  }

  private static boolean isCounterpart(Answerable record, PartySide side) {
    return side != null && record.recordedBy() != null && record.recordedBy() != side;
  }

  private static boolean isObjection(AckState state) {
    return state == AckState.NEED_MORE || state == AckState.DISPUTE;
  }

  /**
   * 다시 요청한 결과.
   *
   * @param state 다음 응답 값. 늘 {@code PENDING}(상대가 새로 고른다)
   * @param rerequestedAt 저장할 마지막 다시 요청 시각
   */
  public record Rerequested(AckState state, Instant rerequestedAt) {}
}
