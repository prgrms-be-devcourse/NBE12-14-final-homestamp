package com.chilsuitjo.back.inspection.dto;

import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import java.util.UUID;

/**
 * 점검 상태를 바꾸는 API의 응답 본문(확인 요청 등).
 *
 * @param id 점검
 * @param status 바뀐 뒤 상태
 */
public record InspectionStatusResponse(UUID id, InspectionStatus status) {}
