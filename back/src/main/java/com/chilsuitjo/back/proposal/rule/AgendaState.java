package com.chilsuitjo.back.proposal.rule;

import com.chilsuitjo.back.proposal.dto.AgendaRef;
import java.util.UUID;

/**
 * 돈 안건의 head 값. {@code AgendaHeadStore}가 head domain의 entity와 이 값을 서로 바꾼다.
 *
 * @param roomRecordId 안건이 속한 호실 기록 ID
 * @param agenda 안건 종류와 대상
 * @param status 현재 안건 상태
 * @param currentProposalId 현재 제안 ID
 * @param agreedProposalId 현재 합의한 제안 ID
 * @param requiredTotal 확인된 지불의 합(03 1-2 "금액": 상대가 [맞아요]를 누른 지불만). {@code REPAIR_BURDEN}만 쓰고 {@code
 *     SETTLEMENT_ITEM}·{@code DEPOSIT}은 {@code null}. repair의 {@code AgendaHeadStore} 구현이 payment에서
 *     읽어 채우며 proposal은 payment를 부르지 않는다(09 3장 방향 표)
 * @param round 현재 제안 회차
 */
public record AgendaState(
    UUID roomRecordId,
    AgendaRef agenda,
    AgendaStatus status,
    UUID currentProposalId,
    UUID agreedProposalId,
    Long requiredTotal,
    int round) {}
