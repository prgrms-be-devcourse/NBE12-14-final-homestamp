package com.chilsuitjo.back.proposal.dto;

import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaStatus;
import java.util.List;
import java.util.UUID;

/**
 * 수리비·정산 화면에서 공통으로 사용하는 돈 안건 응답.
 *
 * @param kind 안건 종류
 * @param targetId 안건 대상 ID
 * @param status 현재 안건 상태
 * @param requiredTotal 수리비만 사용하는 확인된 지불 합계
 * @param pendingTotal 수리비만 사용하는 확인 중인 지불 합계
 * @param agreed 현재 합의한 안
 * @param waiting 상대의 응답을 기다리는 안
 * @param hasChangeProposal 합의한 안을 바꾸자는 제안이 있는지
 * @param baseChanged 확인 금액이 바뀌어 합의가 풀렸는지
 * @param previousAgreed 이전 합의안
 * @param round 현재 제안 회차
 * @param showTalkHint 3회째부터 대화 안내를 표시할지
 * @param history 제안 이력
 * @param availableActions 현재 화면에서 제공할 수 있는 행동
 */
public record AgendaResponse(
    AgendaKind kind,
    UUID targetId,
    AgendaStatus status,
    Long requiredTotal,
    Long pendingTotal,
    ProposalItemResponse agreed,
    ProposalItemResponse waiting,
    boolean hasChangeProposal,
    boolean baseChanged,
    ProposalItemResponse previousAgreed,
    int round,
    boolean showTalkHint,
    List<ProposalItemResponse> history,
    List<String> availableActions) {}
