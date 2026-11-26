package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import java.util.UUID;

/**
 * 수리 진행 행동(시작·종료 요청·종료 확인·아직 안 됐어요, 06 REP-4·5)의 응답 본문. 같은 키로 다시 온 요청이면 수리 건의 지금 값이다(처음 응답 본문은 저장하지
 * 않음, 06 4-0).
 *
 * @param id 수리 건
 * @param status 행동 뒤의 수리 진행
 * @param closedBy 처리 완료면 누가 확인해 끝났나. 아니면 null
 * @param defectAck 행동 뒤의 하자 인정(종료 확인에서 함께 맞아요를 남겼으면 바뀜)
 */
public record RepairProgressResponse(
    UUID id, RepairStatus status, ClosedBy closedBy, AckState defectAck) {}
