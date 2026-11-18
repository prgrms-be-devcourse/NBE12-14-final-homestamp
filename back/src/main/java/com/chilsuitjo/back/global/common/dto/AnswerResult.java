package com.chilsuitjo.back.global.common.dto;

import com.chilsuitjo.back.global.common.AckState;
import java.util.UUID;

/**
 * 응답하기({@code answer*}) 결과. A·B·C의 응답 메서드가 같은 모양으로 돌려주고, D의 확인 카드가 항목마다 모은다.
 *
 * <p><b>정산 끝내기</b>({@code answerFinish})는 요청의 {@code answer}와 결과의 {@code state}가 같은 값이다(06 4-11
 * {@code /api/responses/batch}). {@code DISPUTE}는 받지 않는다({@code INVALID_STATE}).
 *
 * <ul>
 *   <li>[끝내기 확인] → {@code ACK}(정산 완료)
 *   <li>[아직 안 끝났어요] → {@code NEED_MORE}(사유 필수, 끝내기 요청이 닫힘. 다른 기록의 보류와 달리 나중에 {@code ACK}로 바꿀 수 없고
 *       요청한 쪽이 새로 끝내기를 보낸다)
 * </ul>
 *
 * @param targetId 응답한 기록(수리 건, 점검 항목, 지불, 송금, 정산 등)
 * @param versionNo 응답한 판 번호(요청의 {@code seenVersionNo}). 정산 끝내기 확인이면 정산 모습 번호({@code
 *     settlement.snapshot_no}), 판이 없는 기록이면 null
 * @param state 응답한 뒤의 <b>응답 값</b>. DB 상태 칸의 값이 아니다(지불·송금의 {@code response}, 고친 내용의 {@code status}는
 *     칸 값이 따로 있다)
 * @param replayed 같은 키로 다시 온 요청이면 true. 처음 응답을 저장해 두지 않으므로, 대상의 <b>지금 상태</b>를 처음 성공과 같은 모양으로
 *     돌려준다(06 4-0 "상태를 바꾸는 요청")
 */
public record AnswerResult(UUID targetId, Integer versionNo, AckState state, boolean replayed) {}
