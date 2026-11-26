package com.chilsuitjo.back.repair.dto;

/**
 * 종료 확인 요청({@code POST /api/repair-cases/{id}/confirm-close}, 06 REP-5a·3a).
 *
 * @param alsoAckDefect 하자 인정이 "확인 기다림"이면 하자 "맞아요"까지 한 요청에 남길지(행동 {@code CONFIRM_CLOSE} → {@code
 *     RESPOND_DEFECT}, decisions 2026-11-17). 하자 인정이 확인 기다림이 아니거나, 확인하는 사람이 하자를 등록한 측이면(맞아요는 상대 측만,
 *     03 3장) 무시하고 종료 확인만 남긴다. 비어 있으면 false
 * @param seenVersionNo {@code alsoAckDefect}일 때 본 판 번호(하자 응답과 같음, 상세의 {@code content.no}). 지금 판과
 *     다르면 {@code VERSION_CONFLICT}
 */
public record ConfirmCloseRequest(Boolean alsoAckDefect, Integer seenVersionNo) {

  public boolean ackDefect() {
    return Boolean.TRUE.equals(alsoAckDefect);
  }
}
