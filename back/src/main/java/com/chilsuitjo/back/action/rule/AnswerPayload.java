package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.global.common.Answer;

/**
 * 응답 행동의 payload. 서비스는 {@code Map.of("answer", req.answer().name())}로 남기고, {@code apply}는 {@code
 * entry.payloadAs(AnswerPayload.class)}로 읽는다(09 3-1).
 *
 * <p>사유는 payload에 넣지 않고 행동 기록의 {@code reason} 칸({@code ActionCommand.reason}, {@code
 * entry.reason()})에 둔다(06 REP-9 {@code action.reason}, 05 2-3, #155). 한 사실은 한 곳에만 있다.
 */
public record AnswerPayload(Answer answer) {}
