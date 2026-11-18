package com.chilsuitjo.back.global.common.dto;

import com.chilsuitjo.back.global.common.Answer;

/**
 * 응답하기 요청. 모든 {@code /respond} API가 같은 모양을 쓴다(06 4-0).
 *
 * @param answer 고른 값
 * @param reason 보류·이견이면 필수
 * @param seenVersionNo 확인할 때 본 판 번호
 */
public record RespondRequest(Answer answer, String reason, Integer seenVersionNo) {}
