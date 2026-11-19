package com.chilsuitjo.back.action.dto;

import java.util.UUID;

/**
 * 행동 한 줄의 결과.
 *
 * @param target 이 행동의 대상. 만드는 행동(REGISTER)이면 새로 생긴 대상, 호실 기록 자체가 대상이면 {@code ROOM_RECORD}(호실 만들기면 새
 *     호실). 다시 온 요청이면 처음 만든 대상이 그대로 들어 있다
 * @param replayed 같은 (주체, 키)로 이미 남긴 행동이라 새로 남기지 않았으면 true. 컨트롤러는 처음 성공과 같은 상태 코드·모양으로 돌려주고 {@code
 *     Idempotent-Replayed: true}를 붙인다(06 4-0)
 */
public record ActionResult(UUID actionId, long roomSeq, ActionTarget target, boolean replayed) {}
