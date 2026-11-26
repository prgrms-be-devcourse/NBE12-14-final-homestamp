package com.chilsuitjo.back.repair.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 진행 상황 묻기의 서비스 결과. 컨트롤러가 {@link ProgressAskResponse}로 바꾸고 {@code replayed}면 {@code
 * Idempotent-Replayed} 머리를 붙인다(06 4-0).
 */
public record ProgressAsked(UUID id, Instant nextAskAt, boolean replayed) {

  public ProgressAskResponse response() {
    return new ProgressAskResponse(id, nextAskAt);
  }
}
