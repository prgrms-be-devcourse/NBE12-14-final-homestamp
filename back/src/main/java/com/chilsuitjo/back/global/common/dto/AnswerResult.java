package com.chilsuitjo.back.global.common.dto;

import com.chilsuitjo.back.global.common.AckState;
import java.util.UUID;

/**
 * 응답하기({@code answer*}) 결과. A·B·C의 응답 메서드가 같은 모양으로 돌려주고, D의 확인 카드가 항목마다 모은다(06 4-0 "같은 키가 다시 오면").
 *
 * @param targetId 응답한 기록(수리 건, 점검 항목, 지불, 송금, 정산 등)
 * @param versionNo 응답한 판 번호(요청의 {@code seenVersionNo}). 정산 끝내기 확인이면 정산 모습 번호, 판이 없는 기록이면 null
 * @param state 응답한 뒤의 상태
 * @param replayed 같은 키로 다시 온 요청이면 true(처음 결과를 다시 읽어 돌려줌)
 */
public record AnswerResult(UUID targetId, Integer versionNo, AckState state, boolean replayed) {}
