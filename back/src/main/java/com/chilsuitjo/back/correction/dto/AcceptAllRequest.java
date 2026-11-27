package com.chilsuitjo.back.correction.dto;

import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import java.util.List;
import java.util.UUID;

/**
 * [모두 확인](06 4-6 {@code POST /api/corrections/accept-all}, 03 G "상대가 확인").
 *
 * @param targetKind 기록 종류
 * @param targetId 기록 ID
 * @param seenVersionNo 화면에 보인 앞모습(도장 판)의 번호
 * @param correctionIds 화면에 보인 확인 전 고친 내용들. 지금 기다리는 고친 내용과 같아야 한다(다르면 {@code VERSION_CONFLICT})
 */
public record AcceptAllRequest(
    CorrectionTargetKind targetKind,
    UUID targetId,
    Integer seenVersionNo,
    List<UUID> correctionIds) {}
