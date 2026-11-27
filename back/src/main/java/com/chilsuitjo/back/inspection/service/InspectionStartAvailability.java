package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.repository.InspectionStateStore;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.room.service.InspectionStartCheck;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호실 상세의 입주 점검 시작 버튼({@link InspectionStartCheck}). 시작 요청이 읽는 상태({@link
 * InspectionStateStore#beforeStart})와 같은 규칙({@link InspectionTransitions#canStart})으로
 * 판단한다(decisions 2026-11-24). 잠그지 않고 읽으므로, 화면을 연 뒤 상태가 바뀌면 실제 요청이 다시 검사해 거절한다.
 */
@Service
@RequiredArgsConstructor
public class InspectionStartAvailability implements InspectionStartCheck {

  private final InspectionStateStore states;
  private final InspectionTransitions rules;

  @Override
  @Transactional(readOnly = true)
  public boolean canStartMoveIn(UUID roomRecordId, PartySide side) {
    InspectionKind kind = InspectionKind.MOVE_IN;
    return rules.canStart(states.beforeStart(roomRecordId, kind, side), kind);
  }
}
