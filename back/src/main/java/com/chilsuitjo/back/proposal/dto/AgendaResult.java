package com.chilsuitjo.back.proposal.dto;

import com.chilsuitjo.back.proposal.rule.AgendaStatus;
import java.util.List;

/**
 * 서비스에서 돌려주는 안건의 현재 합의·대기 안과 이력.
 *
 * @param agenda 안건 종류와 대상
 * @param status 현재 안건 상태
 * @param agreed 현재 합의한 안
 * @param waiting 상대의 응답을 기다리는 안
 * @param previousAgreed 이전 합의안
 * @param baseChanged 확인 금액이 바뀌어 합의가 풀렸는지
 * @param round 현재 제안 회차
 * @param history 제안 이력
 */
public record AgendaResult(
    AgendaRef agenda,
    AgendaStatus status,
    ProposalItemResult agreed,
    ProposalItemResult waiting,
    ProposalItemResult previousAgreed,
    boolean baseChanged,
    int round,
    List<ProposalItemResult> history) {}
