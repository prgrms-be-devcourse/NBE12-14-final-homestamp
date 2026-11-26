package com.chilsuitjo.back.correction.dto;

import com.chilsuitjo.back.correction.rule.CorrectionResult;
import java.util.List;
import java.util.UUID;

/**
 * 고치기 결과(06 4-6 "응답에 결과와 화면 문구").
 *
 * @param correctionIds 확인 요청이 된 고친 내용들(칸마다 하나). 바로 바뀌었으면 빈 목록
 * @param message 화면에 그대로 보여 줄 한 줄
 */
public record CorrectResponse(CorrectionResult result, List<UUID> correctionIds, String message) {

  public static CorrectResponse of(CorrectionResult result, List<UUID> correctionIds) {
    return new CorrectResponse(result, List.copyOf(correctionIds), result.message());
  }
}
