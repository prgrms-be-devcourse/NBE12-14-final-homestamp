package com.chilsuitjo.back.inspection.dto;

import java.util.UUID;

/**
 * 점검·점검 항목을 만든 결과.
 *
 * @param id 만든 점검 또는 항목
 * @param replayed 같은 키로 다시 온 요청이라 새로 만들지 않았는지(06 4-0)
 */
public record InspectionCreated(UUID id, boolean replayed) {}
