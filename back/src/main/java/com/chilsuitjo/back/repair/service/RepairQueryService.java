package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.repair.dto.RepairCaseDetail;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.repository.RepairContentRepository;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수리 건 조회(06 4-6). 상태를 바꾸지 않아 pipeline을 거치지 않고 읽기 트랜잭션을 쓴다(09 3-1 "트랜잭션 경계": 조회만 하는 서비스는 읽기 트랜잭션을
 * 써도 된다). 행동은 {@link RepairService}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class RepairQueryService {

  /**
   * {@code availableActions}의 이름. API 끝 단어와 같다(06 4-0 "할 수 있는 행동"). 순서는 {@code
   * RepairTransitions.available}이 정한다.
   */
  private static final Map<ActionType, String> ACTION_NAMES =
      Map.of(ActionType.RESPOND_DEFECT, "respond", ActionType.SUPPLEMENT, "supplement");

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final RepairStateStore store;
  private final RepairTransitions rules;
  private final RoomGateReader gates;
  private final RoomQueryService rooms;
  private final Clock clock;

  /**
   * 수리 건 상세(06 4-6, S-12). 없는 ID, 그 호실 당사자가 아님, 상대가 볼 수 없는 기록(공유를 그만둔 뒤 등록, 03 불변식 18)은 모두 {@code
   * NOT_FOUND}다(있는지도 알 수 없게, ROOM-2a).
   *
   * <p>{@code availableActions}는 실제 요청과 같은 판단으로 만든다: 호실 단위 거절은 {@code RoomGate.rejection}(pipeline
   * 3단계와 같음), 수리 건 규칙은 {@code RepairTransitions.available}({@code apply}와 같음). 잠그지 않고 읽으므로 화면을 연 뒤
   * 상태가 바뀔 수 있고, 그때는 요청이 서버에서 다시 검사되어 거절된다.
   */
  @Transactional(readOnly = true)
  public RepairCaseDetail detail(UUID repairCaseId, UUID memberId) {
    UUID roomRecordId =
        cases
            .findRoomRecordIdById(repairCaseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    RepairCase row =
        cases
            .findByIdAndRoomRecordId(repairCaseId, roomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RepairState state = store.current(row);
    if (state.hiddenFromCounterpartKnown() && gate.side() != state.registeredBy()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    RepairContent content =
        contents
            .findById(row.getCurrentContentId())
            .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + row.getId()));
    String partName = rooms.findPart(roomRecordId, content.getRoomPartId()).name();
    List<String> actions =
        rules.available(state, gate.side(), clock.instant()).stream()
            .filter(type -> gate.rejection(type).isEmpty())
            .map(ACTION_NAMES::get)
            .toList();
    return new RepairCaseDetail(
        row.getId(),
        roomRecordId,
        gate.side(),
        row.getCreatedBySide(),
        row.getStatus(),
        row.isSolo(),
        row.getCreatedAt(),
        new RepairCaseDetail.Content(
            content.getNo(),
            content.getRoomPartId(),
            partName,
            content.getTitle(),
            content.getDescription()),
        new RepairCaseDetail.Defect(row.getDefectAck(), rules.nextRerequestAt(state)),
        new RepairCaseDetail.Money(row.getMoneyStatus()),
        actions);
  }
}
