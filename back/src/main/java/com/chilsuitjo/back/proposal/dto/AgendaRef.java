package com.chilsuitjo.back.proposal.dto;

import com.chilsuitjo.back.proposal.rule.AgendaKind;
import java.util.UUID;

/**
 * 돈 안건의 종류와 대상 식별자.
 *
 * @param kind 수리비 부담·정산 항목·보증금 중 안건 종류
 * @param targetId 종류에 해당하는 수리 건·정산 항목·정산의 ID
 */
public record AgendaRef(AgendaKind kind, UUID targetId) {}
