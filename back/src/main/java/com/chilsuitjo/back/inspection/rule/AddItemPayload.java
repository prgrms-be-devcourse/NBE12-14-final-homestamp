package com.chilsuitjo.back.inspection.rule;

import java.util.UUID;

/**
 * 항목 더하기({@code ADD_ITEM})의 payload. 새 항목을 만드는 행동이라 행동 대상은 새 항목이고, 부모 점검은 여기 둔다(안내서 6장 "부모 ID는
 * payload에"). 부위·설명은 판에만 둔다(05 2-3 "payload는 작은 부가 값만").
 *
 * @param inspectionId 항목을 더하는 점검
 */
public record AddItemPayload(UUID inspectionId) {}
