package com.chilsuitjo.back.correction.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;

/**
 * 고치기 규칙({@link CorrectionTransitions})이 보는 지금 상태. 각 {@code StateStore.load}가 잠금 뒤에 읽어 만든다.
 *
 * <p>고치기({@code CORRECT})는 기록 쪽 값을, 응답·거두기·다시 요청({@code RESPOND_CORRECTION}·{@code
 * WITHDRAW_CORRECTION}·{@code RESUBMIT_CORRECTION})은 고친 내용 쪽 값을 채운다. 쓰지 않는 칸은 null·false다.
 *
 * @param owner 고치기면 원래 기록한 측(이 측만 고침), 응답·거두기·다시 요청이면 고친 측(그 반대 측이 답하고, 이 측이 거두고 다시 보냄)
 * @param stamped 고치기: 지금 기록에 도장이 있나
 * @param stampChanged 고치기: 서비스가 잠그기 전에 본 도장 여부와 지금이 다름(그사이 상대가 확인함). 요청 모양이 달라져 다시 불러야 한다
 * @param sameValue 고치기: 새 값 중 앞모습과 같은 칸이 있음
 * @param closedByBoth 고치기: 처리 완료(양측)된 수리 건. 응답·다시 요청: 그 이유로 닫힌 고친 내용이거나 기록이 처리 완료(양측)
 * @param status 응답·거두기·다시 요청: 고친 내용의 지금 상태
 * @param answer 응답·다시 요청: 지금 응답 값(기다림·미확인이면 {@code PENDING}, 거절됐으면 마지막 보류·이견)
 * @param versionNo 응답: 지금 앞모습(도장) 판 번호. 본 번호와 다르면 {@code VERSION_CONFLICT}(REP-7d·7j)
 * @param latestOfField 다시 요청: 이 고친 내용이 그 칸의 마지막 고친 내용인가(그 뒤 같은 칸을 다시 고쳤거나 다시 보냈으면 false)
 * @param canOpenRequest 다시 요청: 지금 호실 단계에서 고친 내용 확인 요청을 열 수 있나(03 K)
 * @param lastRerequestAt 다시 요청: 그 기록의 마지막 다시 요청 시각(같은 기록의 다시 요청끼리 함께 셈, #278)
 */
public record CorrectionState(
    PartySide owner,
    boolean stamped,
    boolean stampChanged,
    boolean sameValue,
    boolean closedByBoth,
    CorrectionStatus status,
    AckState answer,
    Integer versionNo,
    boolean latestOfField,
    boolean canOpenRequest,
    Instant lastRerequestAt) {

  /** 고치기의 지금 상태. */
  public static CorrectionState forCorrecting(
      PartySide recordedBy,
      boolean stamped,
      boolean stampChanged,
      boolean sameValue,
      boolean closedByBoth) {
    return new CorrectionState(
        recordedBy,
        stamped,
        stampChanged,
        sameValue,
        closedByBoth,
        null,
        null,
        null,
        false,
        false,
        null);
  }

  /** 응답·거두기의 지금 상태. */
  public static CorrectionState forAnswering(
      PartySide requestedBy,
      CorrectionStatus status,
      boolean closedByBoth,
      AckState answer,
      Integer versionNo) {
    return new CorrectionState(
        requestedBy,
        false,
        false,
        false,
        closedByBoth,
        status,
        answer,
        versionNo,
        false,
        false,
        null);
  }

  /** [사진 더해서 다시 요청]의 지금 상태. */
  public static CorrectionState forResubmitting(
      PartySide requestedBy,
      CorrectionStatus status,
      boolean closedByBoth,
      AckState answer,
      boolean latestOfField,
      boolean canOpenRequest,
      Instant lastRerequestAt) {
    return new CorrectionState(
        requestedBy,
        false,
        false,
        false,
        closedByBoth,
        status,
        answer,
        null,
        latestOfField,
        canOpenRequest,
        lastRerequestAt);
  }

  /** {@code AnswerSupport}가 보는 모양. */
  public Answerable answerable() {
    return new Answerable(owner, answer, versionNo, lastRerequestAt);
  }

  CorrectionState withAnswer(AckState next) {
    return new CorrectionState(
        owner,
        stamped,
        stampChanged,
        sameValue,
        closedByBoth,
        status,
        next,
        versionNo,
        latestOfField,
        canOpenRequest,
        lastRerequestAt);
  }
}
