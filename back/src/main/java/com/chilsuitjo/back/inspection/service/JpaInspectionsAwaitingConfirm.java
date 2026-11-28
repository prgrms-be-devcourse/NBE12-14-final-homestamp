package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.repository.InspectionItemRepository;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.notification.dto.AwaitingConfirm;
import com.chilsuitjo.back.notification.service.InspectionsAwaitingConfirm;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InspectionsAwaitingConfirm}의 inspection 구현(#317, #399 {@code InviteRequestLinker}). 그 호실에서
 * 확인 요청({@code REQUESTED}) 상태이고 아직 답을 다 받지 않은 점검을 읽기만 한다.
 *
 * <p>{@code baseSide}는 점검을 쓴 측({@code author_side})이다. 확인 요청 보내기는 상대 측이 "지금까지 쓴 것으로" 보내도 쓴 측을 기준
 * 측으로 응답 요청을 연다({@code InspectionStateStore} "Requesting", decisions 2026-11-25). 상대 측이 더한 항목의 확인
 * 요청(요청한 측 = 상대 측)은 여기서 주지 않는다: 초대받는 자리는 아직 연결 전이라 그 자리가 항목을 더했을 수 없다.
 *
 * <p>답을 받은 점검은 주지 않는다(포트 계약: 주면 닫힌 요청 대신 새 요청이 하나 더 열린다). 답을 받았는지는 inspection이 요청을 닫을 때와 같은 기준으로
 * 본다: 쓴 측 항목 가운데 미응답({@code PENDING}·{@code UNCONFIRMED})이 하나도 없으면 답함이다({@code
 * InspectionItemStateStore.answerIfSettled}). 쓴 측 항목이 없는 확인 요청 점검은 "하자 기록 없음" 확인(INSP-1c)을 기다리는 것이라
 * 준다(그 확인을 받으면 잠겨 확인 요청 상태가 아니게 된다). 작성 중·잠김 점검은 주지 않는다.
 *
 * <p>초대 {@code save} 안(호실 행 잠금 뒤)에서만 불린다. 그래서 트랜잭션은 부르는 쪽 것에 참여하고, 없으면 거절한다. 읽지 못하면 예외를 그대로 올린다(빈
 * 목록으로 바꾸지 않음).
 */
@Service
@RequiredArgsConstructor
public class JpaInspectionsAwaitingConfirm implements InspectionsAwaitingConfirm {

  private static final Set<AckState> UNANSWERED = Set.of(AckState.PENDING, AckState.UNCONFIRMED);

  private final InspectionRepository inspections;
  private final InspectionItemRepository items;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<AwaitingConfirm> of(UUID roomRecordId) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    return inspections
        .findByRoomRecordIdAndStatusOrderByCreatedAtAsc(roomRecordId, InspectionStatus.REQUESTED)
        .stream()
        .filter(this::awaitingAnswer)
        .map(row -> new AwaitingConfirm(row.getId(), row.getAuthorSide()))
        .toList();
  }

  /** 쓴 측 항목이 없거나, 쓴 측 항목 가운데 미응답이 남아 있다. */
  private boolean awaitingAnswer(Inspection row) {
    var authored =
        items.findByInspectionIdOrderByIdAsc(row.getId()).stream()
            .filter(item -> item.getAddedBySide() == row.getAuthorSide())
            .toList();
    return authored.isEmpty()
        || authored.stream().anyMatch(i -> UNANSWERED.contains(i.getStatus()));
  }
}
