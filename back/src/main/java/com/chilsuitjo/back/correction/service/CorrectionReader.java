package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.repository.CorrectionStateStore;
import com.chilsuitjo.back.correction.rule.CorrectionState;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.rule.CorrectionTransitions;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 기록의 고친 내용 읽기(상세 화면용). 잠그지 않고 읽는다. 보이는 범위(공유 중단 뒤 고정)는 부르는 도메인이 거른다. */
@Service
@RequiredArgsConstructor
public class CorrectionReader {

  private final CorrectionStateStore store;
  private final CorrectionTransitions rules;

  /** 그 기록의 고친 내용 모두(만든 순서). */
  public List<CorrectionView> findByRecord(CorrectionTargetKind kind, UUID targetId) {
    return store.views(kind.targetKey(targetId));
  }

  /**
   * 그 기록에서 고친 내용을 마지막으로 다시 보낸 시각. 없으면 null. 대상 도메인은 자기 다시 요청([보충하고 다시 확인 요청] 등)의 7일을 셀 때 이 시각도 함께
   * 센다(같은 기록의 다시 요청끼리, #278).
   */
  public Instant lastResubmitAt(CorrectionTargetKind kind, UUID targetId) {
    return store.lastResubmitAt(kind.targetKey(targetId));
  }

  /** 그 기록의 고친 내용 ID 모두. 응답까지 읽지 않는다(사진 한도를 셀 때). */
  public List<UUID> idsByRecord(CorrectionTargetKind kind, UUID targetId) {
    return store.ids(kind.targetKey(targetId));
  }

  /**
   * 화면의 [사진 더해서 다시 요청] 버튼(06 4-0)을 누를 수 있는 고친 내용들. 실제 요청과 같은 규칙({@code CorrectionTransitions})이고
   * 근거는 더한다고 본다. 거절된 것만 보고, "그 칸의 마지막"은 넘겨받은 목록에서 한 번에 판단해 고친 내용마다 다시 읽지 않는다(#296 리뷰). 값이 지금도 맞는지
   * (숨긴 부위 등)는 실제 요청에서만 본다. 호실 단위 거절은 부르는 쪽이 {@code RoomGate.rejection}으로 본다.
   *
   * @param views 그 기록의 고친 내용 모두({@link #findByRecord}, 보이는 범위로 거르기 전)
   * @param lastRerequestAt 그 기록의 마지막 다시 요청 시각({@code Correctable.lastRerequestAt}과 같은 값)
   */
  public Set<UUID> resubmittable(
      UUID roomRecordId,
      List<CorrectionView> views,
      PartySide side,
      Instant lastRerequestAt,
      Instant now) {
    Map<String, UUID> lastOfField = new HashMap<>();
    for (CorrectionView view : views) {
      lastOfField.put(view.field(), view.id());
    }
    Set<UUID> result = new HashSet<>();
    Boolean canOpen = null;
    for (CorrectionView view : views) {
      if (view.status() != CorrectionStatus.REJECTED
          || !view.id().equals(lastOfField.get(view.field()))) {
        continue;
      }
      if (canOpen == null) {
        canOpen = store.canOpenConfirm(roomRecordId);
      }
      CorrectionState state =
          CorrectionState.forResubmitting(
              view.requestedBySide(),
              view.status(),
              view.closedReason() == ClosedReason.CLOSED_BY_BOTH,
              view.answer(),
              true,
              canOpen,
              lastRerequestAt,
              false);
      if (rules.canResubmit(state, side, now)) {
        result.add(view.id());
      }
    }
    return Set.copyOf(result);
  }

  /** 다음에 다시 요청할 수 있는 시각("○월 ○일부터 다시 요청할 수 있어요"). 다시 요청한 적이 없으면 null. */
  public Instant nextRerequestAt(Instant lastRerequestAt) {
    return rules.nextRerequestAt(lastRerequestAt);
  }
}
