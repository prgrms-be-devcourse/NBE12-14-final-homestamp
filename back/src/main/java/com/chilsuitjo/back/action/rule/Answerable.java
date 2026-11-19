package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;

/**
 * 응답을 받는 기록 하나를 {@link AnswerSupport}가 보는 모양(#86). 수리 건 하자, 점검 항목, 지불, 송금, 고친 내용 등이 각자 자기 State에서 이
 * 값을 만들어 넘긴다.
 *
 * @param recordedBy 기록한 측. 그 반대 측이 답하고, 이 측이 보충한다. <b>각 도메인이 정해서 넣는다</b>: 송금은 보낸 쪽이 기록했으면 받은 쪽이,
 *     [받았어요(혼자)]로 기록했으면 보낸 쪽이 답한다(SET-4). {@code AnswerSupport}는 이 규칙을 모른다
 * @param state 지금 응답 값
 * @param versionNo 지금 판 번호. 판이 없는 기록(송금 등)이면 null이고, 그때는 본 판 번호를 비교하지 않는다
 * @param lastRerequestAt 마지막으로 다시 요청한 시각(보충·사진 더해서 다시 요청). 없으면 null. 행동 기록이 원본이고 각 도메인 {@code
 *     load}가 읽어 담는다. 첫 요청은 세지 않는다(7일은 다시 요청끼리만, #86)
 */
public record Answerable(
    PartySide recordedBy, AckState state, Integer versionNo, Instant lastRerequestAt) {}
