package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.repository.CorrectionStateStore;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.rule.CorrectionTransitions;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.List;
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

  /**
   * 화면의 [사진 더해서 다시 요청] 버튼(06 4-0). 실제 요청과 같은 규칙({@code CorrectionTransitions})이고 근거는 더한다고 본다. 호실 단위
   * 거절은 부르는 쪽이 {@code RoomGate.rejection}으로 본다.
   *
   * @param lastRerequestAt 그 기록의 마지막 다시 요청 시각({@code Correctable.lastRerequestAt}과 같은 값)
   */
  public boolean canResubmit(
      UUID correctionId, PartySide side, Instant lastRerequestAt, Instant now) {
    return rules.canResubmit(store.resubmitState(correctionId, lastRerequestAt), side, now);
  }

  /** 다음에 다시 요청할 수 있는 시각("○월 ○일부터 다시 요청할 수 있어요"). 다시 요청한 적이 없으면 null. */
  public Instant nextRerequestAt(Instant lastRerequestAt) {
    return rules.nextRerequestAt(lastRerequestAt);
  }
}
