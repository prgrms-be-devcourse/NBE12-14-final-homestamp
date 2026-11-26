package com.chilsuitjo.back.repair.rule;

/**
 * 처리 완료를 누가 확인했나(03 D-2 "처리 완료(○○ 확인 / 양측)"). 값은 V1 {@code repair_case.closed_by} CHECK와 같다.
 *
 * <p>한쪽({@code TENANT}·{@code LANDLORD})은 물을 상대가 없을 때 그 측이 혼자 끝낸 건이다(06 REP-5e). 상대는 정산 완료 전까지 늦게
 * 종료 확인해 {@code BOTH}로 만들거나 이견을 남길 수 있다(REP-5f, 상태는 그대로). 다시 열기({@code REOPEN})는 없앴다(#113 ③',
 * decisions 2026-11-25)라 처리 완료에서 이 값이 비는 일은 없다(V1 CHECK {@code (status = 'DONE') = (closed_by IS
 * NOT NULL)}).
 */
public enum ClosedBy {
  BOTH,
  TENANT,
  LANDLORD
}
