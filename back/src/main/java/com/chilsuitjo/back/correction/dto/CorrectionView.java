package com.chilsuitjo.back.correction.dto;

import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.UUID;

/**
 * 한 기록의 고친 내용 하나(03 G "화면 규칙 네 줄"). 상세 화면이 앞모습 아래 상자(기다림·보류·이견)와 [고친 이력 ▸](확인됨·거둠·대신됨·닫힘)를 나눠 그린다.
 *
 * @param newValue 새 값(칸마다 모양이 다름, 수리 건 부위면 부위 ID 글자)
 * @param requestedBySide 고친 측
 * @param reason 고친 이유
 * @param answer 상대의 지금 응답. 기다림·미확인은 {@code PENDING}, 확인됨이면 {@code ACK}, 거절이면 {@code
 *     NEED_MORE}(보류)·{@code DISPUTE}(이견)
 * @param answerReason 보류·이견의 사유. 그 밖에는 null
 * @param answeredAt 마지막 응답 시각. 응답이 없으면 null
 * @param closedReason 닫혔으면 이유(예: 처리 완료로 닫힘). 그 밖에는 null
 */
public record CorrectionView(
    UUID id,
    String field,
    Object newValue,
    PartySide requestedBySide,
    String reason,
    CorrectionStatus status,
    AckState answer,
    String answerReason,
    Instant answeredAt,
    ClosedReason closedReason,
    Instant createdAt) {}
