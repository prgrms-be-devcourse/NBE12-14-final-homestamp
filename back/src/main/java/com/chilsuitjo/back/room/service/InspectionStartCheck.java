package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.UUID;

/**
 * 호실 상세가 입주 점검 시작 버튼({@code availableActions}의 {@code inspections}, 06 4-3)을 보일지 묻는 창구. 점검 규칙은
 * {@code inspection}에 있고 {@code inspection → room}이 이미 있어, {@code room}이 이 인터페이스를 두고 {@code
 * inspection}이 구현한다(09 3장, {@code repair}의 입주 기록 읽기와 같은 방식).
 *
 * <p>호실 단위 거절(정산 완료·공유 중단 등)은 여기서 보지 않는다. 부르는 쪽이 {@code RoomGate.rejection}으로 먼저 본다.
 */
public interface InspectionStartCheck {

  /**
   * 이 측이 지금 이 호실의 입주 점검({@code MOVE_IN})을 시작할 수 있나. 실제 시작 요청({@code POST
   * /api/rooms/{id}/inspections})과 같은 판단이다: 이미 입주 점검이 있거나 거주 종료 뒤면 거짓이다. 퇴거 점검 시작은 보지 않는다.
   */
  boolean canStartMoveIn(UUID roomRecordId, PartySide side);
}
