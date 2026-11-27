package com.chilsuitjo.back.member.dto;

import com.chilsuitjo.back.member.rule.AcknowledgementKind;

/**
 * 다시 확인해야 할 문구 하나({@code GET /api/me}의 {@code pendingAcknowledgements}, 06 4-1, CONS-4). 화면은 이 판의
 * 문구를 보여 주고 같은 {@code kind}·{@code textVersion}으로 {@code POST /api/me/agreements}를 부른다.
 *
 * @param textVersion 확인할 지금 판
 */
public record PendingAcknowledgement(AcknowledgementKind kind, String textVersion) {}
