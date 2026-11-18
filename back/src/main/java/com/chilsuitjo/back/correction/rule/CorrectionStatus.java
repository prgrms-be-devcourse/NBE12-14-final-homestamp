package com.chilsuitjo.back.correction.rule;

/**
 * 고친 내용의 상태(03 G). 값은 V1 {@code correction_request.status} CHECK와 같다.
 *
 * <p>{@code REJECTED}는 보류·이견의 공통 처리 상태이고 응답 종류가 아니다. 보류·이견 구분과 사유는 {@code RESPOND_CORRECTION} 행동의
 * payload에 남는다(05 2-5, decisions 2026-11-16).
 */
public enum CorrectionStatus {
  WAITING,
  ACCEPTED,
  REJECTED,
  UNCONFIRMED,
  WITHDRAWN,
  SUPERSEDED,
  CLOSED
}
