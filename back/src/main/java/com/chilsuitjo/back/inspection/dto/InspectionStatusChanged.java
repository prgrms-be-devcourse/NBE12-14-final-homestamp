package com.chilsuitjo.back.inspection.dto;

import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import java.util.UUID;

/**
 * 점검 상태를 바꾼 결과(확인 요청 등).
 *
 * @param id 점검
 * @param status 바뀐 뒤 상태. 같은 키로 다시 온 요청이면 지금 상태다(06 4-0)
 * @param replayed 같은 키로 다시 온 요청인지
 */
public record InspectionStatusChanged(UUID id, InspectionStatus status, boolean replayed) {}
