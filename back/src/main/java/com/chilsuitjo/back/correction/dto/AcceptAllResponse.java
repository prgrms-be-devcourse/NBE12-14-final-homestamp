package com.chilsuitjo.back.correction.dto;

import java.util.List;
import java.util.UUID;

/**
 * [모두 확인] 결과.
 *
 * @param correctionIds 확인한 고친 내용들(요청 순서)
 */
public record AcceptAllResponse(List<UUID> correctionIds) {

  public AcceptAllResponse {
    correctionIds = List.copyOf(correctionIds);
  }
}
