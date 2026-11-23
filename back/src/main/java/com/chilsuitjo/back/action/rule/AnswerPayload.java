package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.global.common.Answer;

/**
 * 응답 행동의 payload. 서비스는 {@code Map.of("answer", req.answer(), "reason", req.reason())}로 남기고, {@code
 * apply}는 {@code entry.payloadAs(AnswerPayload.class)}로 읽는다(09 3-1). 사유가 없으면 {@code "reason"} 키를 넣지
 * 않는다({@code Map.of}는 null 값을 받지 않음).
 */
public record AnswerPayload(Answer answer, String reason) {}
