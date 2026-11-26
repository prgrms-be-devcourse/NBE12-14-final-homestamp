package com.chilsuitjo.back.inspection.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * 남은 항목 모두 확인 요청 본문(06 4-7 {@code confirm-all}). 화면에서 본 미응답 항목과 그 판 번호를 모두 보낸다. 지금 미응답 항목과 하나라도 다르면
 * {@code VERSION_CONFLICT}다(#235). 항목이 없는 점검이면 빈 목록이다("하자 기록 없음" 확인).
 */
public record ConfirmAllRequest(@NotNull List<@Valid @NotNull Seen> items) {

  /**
   * 본 항목 하나.
   *
   * @param seenVersionNo 확인할 때 본 판 번호({@code InspectionDetail.Version.no})
   */
  public record Seen(@NotNull UUID id, @NotNull Integer seenVersionNo) {}
}
