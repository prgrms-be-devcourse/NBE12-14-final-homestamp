package com.chilsuitjo.back.repair.rule;

/**
 * 처리 완료를 누가 확인했나(03 D-2 "처리 완료(○○ 확인 / 양측)"). 값은 V1 {@code repair_case.closed_by} CHECK와 같다.
 *
 * <p>V1 CHECK {@code (status = 'DONE') = (closed_by IS NOT NULL)}이라 다시 열기({@code REOPEN})는 이 값을
 * 비운다.
 */
public enum ClosedBy {
  BOTH,
  TENANT,
  LANDLORD
}
