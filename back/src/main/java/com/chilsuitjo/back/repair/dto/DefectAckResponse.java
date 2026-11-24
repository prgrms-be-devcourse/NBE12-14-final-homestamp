package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import java.util.UUID;

/**
 * 보충하고 다시 확인 요청의 응답 본문({@code POST /api/repair-cases/{id}/supplement}).
 *
 * @param id 수리 건
 * @param defectAck 보충한 뒤의 하자 인정
 */
public record DefectAckResponse(UUID id, AckState defectAck) {}
