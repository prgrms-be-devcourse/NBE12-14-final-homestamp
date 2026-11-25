package com.chilsuitjo.back.repair.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 진행 상황 묻기(06 REP-6b)의 응답 본문. 같은 키로 다시 온 요청이면 수리 건의 지금 값이다(06 4-0).
 *
 * @param id 수리 건
 * @param nextAskAt 다음에 물을 수 있는 시각("○월 ○일부터 다시 물을 수 있어요", 누가 물었든 한 건에 7일에 한 번)
 */
public record ProgressAskResponse(UUID id, Instant nextAskAt) {}
