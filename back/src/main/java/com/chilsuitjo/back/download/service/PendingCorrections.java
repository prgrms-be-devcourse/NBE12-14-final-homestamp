package com.chilsuitjo.back.download.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.CorrectionReader;
import com.chilsuitjo.back.download.dto.RoomDownload.PendingCorrection;
import com.chilsuitjo.back.download.dto.RoomDownload.Target;
import com.chilsuitjo.back.download.rule.RecordVisibility;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 내려받기에 따로 담는 확인 전 고친 내용(03 G "확인 전 고친 내용은 상태와 함께 따로 담는다"). 고친 내용은 {@code correction}의 읽기 창구({@link
 * CorrectionReader})로 기록마다 읽는다.
 *
 * <p>보이는 범위는 수리 건 상세의 고친 내용과 같다(03 6-6 9번, 불변식 18): 기준 시각 뒤에 상대가 고친 것은 빼고, 응답은 기준 시각까지의 응답과 내 응답 중
 * 마지막으로 다시 정한다(맞아요 → 확인됨이라 빠짐, 보류·이견 → 거절, 없음 → 기다림). 기준 시각은 공유 중단({@code shared}), 없으면 상대가 탈퇴했을 때의
 * 중단 시각({@code departed})이다.
 */
@Component
@RequiredArgsConstructor
class PendingCorrections {

  /** 같이 확인하기 전으로 남은 상태(03 G). 확인됨·거둠·대신됨·닫힘은 빠진다. */
  static final Set<CorrectionStatus> PENDING =
      EnumSet.of(CorrectionStatus.WAITING, CorrectionStatus.REJECTED, CorrectionStatus.UNCONFIRMED);

  private final CorrectionReader corrections;

  /**
   * 호실의 확인 전 고친 내용. 고칠 수 있는 기록(수리 건·지불·점검 항목·정산 송금)은 호실 행동의 대상에서 찾고, 입주일은 호실 기록 자체다.
   *
   * @param all 호실 행동 전체(보이는 범위로 거르기 전). 상대만 남긴 기록에도 내 고친 내용이 있을 수 있다
   */
  List<PendingCorrection> of(
      UUID roomRecordId, List<ActionEntry> all, PartySide viewer, RecordVisibility.Cutoff cutoff) {
    Instant limit = cutoff.shared() != null ? cutoff.shared() : cutoff.departed();
    List<PendingCorrection> result = new ArrayList<>();
    for (Record record : records(roomRecordId, all)) {
      for (CorrectionView view : corrections.findByRecord(record.kind(), record.id())) {
        if (hidden(view.requestedBySide(), view.createdAt(), viewer, limit)) {
          continue;
        }
        Shown shown = shown(view, viewer, limit);
        if (!PENDING.contains(shown.status())) {
          continue;
        }
        result.add(
            new PendingCorrection(
                view.id(),
                record.kind() == CorrectionTargetKind.MOVE_IN_DATE
                    ? null
                    : new Target(record.kind().targetKind(), record.id()),
                view.field(),
                view.newValue(),
                view.requestedBySide(),
                view.reason(),
                view.createdAt(),
                shown.status(),
                shown.answer(),
                shown.reason(),
                shown.at()));
      }
    }
    result.sort(
        Comparator.comparing(PendingCorrection::createdAt).thenComparing(PendingCorrection::id));
    return List.copyOf(result);
  }

  private record Record(CorrectionTargetKind kind, UUID id) {}

  private static Set<Record> records(UUID roomRecordId, List<ActionEntry> all) {
    Set<Record> records = new LinkedHashSet<>();
    records.add(new Record(CorrectionTargetKind.MOVE_IN_DATE, roomRecordId));
    for (ActionEntry entry : all) {
      if (entry.target() == null) {
        continue;
      }
      for (CorrectionTargetKind kind : CorrectionTargetKind.values()) {
        if (kind != CorrectionTargetKind.MOVE_IN_DATE
            && kind.targetKind() == entry.target().kind()
            && entry.target().kind() != TargetKind.ROOM_RECORD) {
          records.add(new Record(kind, entry.target().id()));
        }
      }
    }
    return records;
  }

  private static boolean hidden(PartySide side, Instant at, PartySide viewer, Instant limit) {
    return limit != null && side != viewer && at.isAfter(limit);
  }

  private record Shown(CorrectionStatus status, AckState answer, String reason, Instant at) {}

  /** 보는 사람에게 보이는 응답. 가린 응답이 없으면 지금 값이다(수리 건 상세 {@code shownAnswer}와 같은 판단). */
  private static Shown shown(CorrectionView view, PartySide viewer, Instant limit) {
    Shown now = new Shown(view.status(), view.answer(), view.answerReason(), view.answeredAt());
    CorrectionView.Response last = null;
    boolean anyHidden = false;
    for (CorrectionView.Response response : view.responses()) {
      if (hidden(response.side(), response.at(), viewer, limit)) {
        anyHidden = true;
      } else {
        last = response;
      }
    }
    if (!anyHidden) {
      return now;
    }
    if (last == null) {
      return new Shown(CorrectionStatus.WAITING, AckState.PENDING, null, null);
    }
    CorrectionStatus status =
        last.answer() == AckState.ACK ? CorrectionStatus.ACCEPTED : CorrectionStatus.REJECTED;
    return new Shown(status, last.answer(), last.reason(), last.at());
  }
}
