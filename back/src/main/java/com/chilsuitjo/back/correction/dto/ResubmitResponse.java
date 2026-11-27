package com.chilsuitjo.back.correction.dto;

import java.util.UUID;

/**
 * [사진 더해서 다시 요청] 결과(06 4-6 {@code resubmit}).
 *
 * @param correctionId 새로 만든 고친 내용(거절된 것을 {@code retryOfId}로 가리킴). 상대는 이 ID로 답한다
 */
public record ResubmitResponse(UUID correctionId) {}
