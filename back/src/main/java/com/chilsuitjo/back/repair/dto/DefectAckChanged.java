package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import java.util.UUID;

/**
 * 보충하고 다시 확인 요청(06 REP-2e)의 결과.
 *
 * @param id 수리 건
 * @param defectAck 보충한 뒤의 하자 인정(처음이면 늘 {@code PENDING}). 같은 키로 다시 온 요청이면 수리 건의 지금 값이다(06 4-0)
 * @param replayed 다시 온 요청인지. 응답 본문에는 넣지 않고 {@code Idempotent-Replayed} 머리로 알린다
 */
public record DefectAckChanged(UUID id, AckState defectAck, boolean replayed) {}
