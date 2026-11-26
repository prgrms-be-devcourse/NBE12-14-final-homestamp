package com.chilsuitjo.back.correction.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.correction.dto.AppliedVersion;
import com.chilsuitjo.back.correction.dto.CorrectableSnapshot;
import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.entity.CorrectionRequest;
import com.chilsuitjo.back.correction.rule.CorrectionState;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.Correctable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.WaitService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 고치기·고친 내용 응답·거두기를 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * CorrectionTransitions}에만). 기록의 판과 머리는 그 기록의 {@link Correctable}이 다룬다.
 *
 * <p>행동의 대상과 내용 지문(05 2-3, 안내서 3-3):
 *
 * <ul>
 *   <li>도장 전 고치기: 행동 하나, 대상은 기록(수리 건 등), 지문은 새로 만든 판
 *   <li>도장 뒤 고치기: 칸마다 행동 하나, 대상은 새 고친 내용({@code correction_request_id}), 지문은 그 고친 내용 행(판 종류 {@code
 *       correction_request}, 판 번호 1, 보충 행과 같음)
 *   <li>응답·거두기: 대상은 고친 내용. 응답의 지문은 본 판(서비스가 넘김), 거두기는 판을 보지 않아 없음
 * </ul>
 *
 * <p>고친 내용 확인 요청({@code CORRECTION_CONFIRM})은 기록당 하나다(05 2-4, REP-7h): 도장 뒤 고칠 때마다 {@code
 * WaitService.restart}로 묶고, 응답으로 그 기록에 기다리는 고친 내용이 0개가 되면 답함으로 닫는다.
 */
@Component
@RequiredArgsConstructor
public class CorrectionStateStore {

  /** 고친 내용 행의 내용 지문 판 종류(05 2-3 「해시 입력 형식」 내용 지문). 판 번호가 없어 1로 둔다(보충 행과 같음). */
  static final String CONTENT_KIND = "correction_request";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CorrectionRequestRepository corrections;
  private final WaitService waits;
  private final ActionReader actions;
  private final ActionRequestContext requestContext;

  /**
   * 도장 전 고치기(REP-7a)의 저장 방법.
   *
   * @param changes 칸 → 새 값(화면이 보낸 모양). 잠근 뒤 {@link Correctable#normalize}로 확인한다
   */
  public StateStore<CorrectionState> applying(
      Correctable target, UUID targetId, Map<String, Object> changes, UUID memberId) {
    return new Applying(target, targetId, changes, memberId);
  }

  /** 도장 뒤 고치기(REP-7c·7h)의 저장 방법. 칸 하나마다 하나다. */
  public StateStore<CorrectionState> requesting(
      Correctable target,
      UUID targetId,
      String field,
      Object newValue,
      UUID memberId,
      String reason) {
    return new Requesting(target, targetId, field, newValue, memberId, reason);
  }

  /** 고친 내용 응답(REP-7e·7i·7m)의 저장 방법. */
  public StateStore<CorrectionState> responding(Correctable target) {
    return new Responding(target, null, null);
  }

  /**
   * [모두 확인]의 저장 방법. 한 묶음의 모든 {@code RESPOND_CORRECTION} 단계가 이 하나를 함께 쓴다.
   *
   * <p>본 번호는 묶음을 시작할 때의 앞모습이다: 앞 단계의 맞아요가 도장 판을 하나씩 올리므로 그만큼 빼고 비교한다(호실 잠금 안이라 그사이 다른 행동은 끼지 않음). 첫
   * 단계에서 화면이 본 고친 내용 목록과 지금 기다리는 목록이 다르면 {@code VERSION_CONFLICT}다(그사이 더 고쳤거나 거뒀음).
   *
   * @param targetKey 그 기록의 대상 글자. 다른 기록의 고친 내용이 섞이면 {@code VALIDATION_FAILED}
   * @param seenIds 화면이 본 확인 전 고친 내용들
   */
  public StateStore<CorrectionState> acceptingAll(
      Correctable target, String targetKey, Set<UUID> seenIds) {
    return new Responding(target, targetKey, Set.copyOf(seenIds));
  }

  /** 고친 내용 거두기의 저장 방법. */
  public StateStore<CorrectionState> withdrawing() {
    return new Withdrawing();
  }

  /** 고친 내용. 없으면 {@code NOT_FOUND}. 잠그기 전에 호실·대상을 알아낼 때 쓴다. */
  public CorrectionRequest find(UUID correctionId) {
    return corrections
        .findById(correctionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /** 고친 내용 행의 내용 지문 재료. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3). */
  static DigestInput digest(CorrectionRequest row) {
    return new DigestInput(
        CONTENT_KIND,
        row.getId(),
        1,
        List.of(
            new DigestField("target_key", row.getTargetKey()),
            new DigestField("field", row.getField()),
            new DigestField("new_value", row.getNewValue()),
            new DigestField(
                "base_version_id",
                row.getBaseVersionId() == null ? null : row.getBaseVersionId().toString()),
            new DigestField("reason", row.getReason())));
  }

  /** 고친 내용의 새 값(JSON 글자 → 값). */
  static Object valueOf(CorrectionRequest row) {
    return JSON.readValue(row.getNewValue(), Object.class);
  }

  private static RequestTarget recordRequest(CorrectionTargetKind kind, UUID targetId) {
    return RequestTarget.of(kind.targetKind(), targetId);
  }

  /** 잠근 뒤 기록의 지금 모습. 다른 호실의 기록이면 {@code NOT_FOUND}(ROOM-2a). */
  private static CorrectableSnapshot snapshot(
      Correctable target, UUID targetId, ActionCommand command) {
    CorrectableSnapshot snapshot = target.load(targetId);
    if (!snapshot.roomRecordId().equals(command.roomRecordId())) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return snapshot;
  }

  /** 새 값 중 앞모습과 같은 칸이 있나. */
  private static boolean anySame(CorrectableSnapshot snapshot, Map<String, Object> normalized) {
    return normalized.entrySet().stream()
        .anyMatch(
            change -> Objects.equals(snapshot.fields().get(change.getKey()), change.getValue()));
  }

  private final class Applying implements StateStore<CorrectionState> {

    private final Correctable target;
    private final UUID targetId;
    private final Map<String, Object> changes;
    private final UUID memberId;
    private Map<String, Object> normalized;

    private Applying(
        Correctable target, UUID targetId, Map<String, Object> changes, UUID memberId) {
      this.target = target;
      this.targetId = targetId;
      this.changes = changes;
      this.memberId = memberId;
    }

    @Override
    public CorrectionState load(ActionCommand command) {
      CorrectableSnapshot snapshot = snapshot(target, targetId, command);
      normalized = target.normalize(targetId, changes, memberId);
      return CorrectionState.forCorrecting(
          snapshot.recordedBySide(),
          snapshot.stamped(),
          snapshot.stamped(), // 도장 전으로 보고 만든 요청인데 그사이 도장이 찍힘
          anySame(snapshot, normalized),
          snapshot.closedByBoth());
    }

    /** 새 판을 만들어 바로 바꾼다. 행동의 대상은 기록이고 지문은 새 판이다. 응답 요청은 만들지 않는다(REP-7a). */
    @Override
    public SavedState save(ActionCommand command, CorrectionState next) {
      AppliedVersion applied = target.applyNow(targetId, normalized, command.actor().side());
      return new SavedState(command.target(), applied.digest());
    }
  }

  private final class Requesting implements StateStore<CorrectionState> {

    private final Correctable target;
    private final UUID targetId;
    private final String field;
    private final Object newValue;
    private final UUID memberId;
    private final String reason;
    private CorrectableSnapshot snapshot;
    private Object normalized;

    private Requesting(
        Correctable target,
        UUID targetId,
        String field,
        Object newValue,
        UUID memberId,
        String reason) {
      this.target = target;
      this.targetId = targetId;
      this.field = field;
      this.newValue = newValue;
      this.memberId = memberId;
      this.reason = reason;
    }

    @Override
    public CorrectionState load(ActionCommand command) {
      snapshot = snapshot(target, targetId, command);
      Map<String, Object> one = new HashMap<>();
      one.put(field, newValue);
      Map<String, Object> checked = target.normalize(targetId, one, memberId);
      normalized = checked.get(field);
      return CorrectionState.forCorrecting(
          snapshot.recordedBySide(),
          snapshot.stamped(),
          !snapshot.stamped(), // 도장 뒤로 보고 만든 요청인데 도장이 없음
          anySame(snapshot, checked),
          snapshot.closedByBoth());
    }

    /**
     * 칸 하나의 고친 내용을 남긴다. 앞모습은 그대로다(REP-7c). 같은 칸에 기다리는 고친 내용이 있으면 먼저 {@code SUPERSEDED}로 바꿔 DB에
     * 내보낸다(칸마다 하나 제약). 기준 판은 지금 도장 판이다. 고친 내용 확인 요청은 기록당 하나로 묶는다(REP-7h).
     */
    @Override
    public SavedState save(ActionCommand command, CorrectionState next) {
      CorrectionTargetKind kind = target.kind();
      String targetKey = kind.targetKey(targetId);
      corrections
          .findByTargetKeyAndFieldAndStatus(targetKey, field, CorrectionStatus.WAITING)
          .ifPresent(earlier -> {
            earlier.supersede();
            corrections.saveAndFlush(earlier);
          });
      CorrectionRequest row =
          corrections.saveAndFlush(
              new CorrectionRequest(
                  command.roomRecordId(),
                  field,
                  JSON.writeValueAsString(normalized),
                  snapshot.confirmedVersionId(),
                  kind == CorrectionTargetKind.REPAIR_CASE ? targetId : null,
                  kind == CorrectionTargetKind.PAYMENT ? targetId : null,
                  kind == CorrectionTargetKind.INSPECTION_ITEM ? targetId : null,
                  kind == CorrectionTargetKind.SETTLEMENT_TRANSFER ? targetId : null,
                  targetKey,
                  null,
                  command.actor().side(),
                  reason,
                  requestContext.at()));
      waits.restart(
          new OpenRequestCommand(
              command.roomRecordId(),
              RequestKind.CORRECTION_CONFIRM,
              recordRequest(kind, targetId),
              command.actor().side()));
      return new SavedState(ActionTarget.correctionRequest(row.getId()), digest(row));
    }
  }

  /** 응답·거두기가 함께 쓰는 고친 내용 읽기. 다른 호실이면 {@code NOT_FOUND}(ROOM-2a). */
  private CorrectionRequest row(ActionCommand command) {
    return corrections
        .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /**
   * 고친 내용의 지금 응답 값. 기다림·미확인은 {@code PENDING}, 확인됐으면 {@code ACK}, 거절됐으면 마지막 응답 행동의 답(보류·이견,
   * decisions 2026-11-16: 처리 상태는 {@code REJECTED} 하나이고 구분은 행동 기록에 있음).
   */
  private AckState answerOf(CorrectionRequest row) {
    return answerOf(row, lastResponse(row));
  }

  private static AckState answerOf(CorrectionRequest row, ActionEntry last) {
    return switch (row.getStatus()) {
      case ACCEPTED -> AckState.ACK;
      case REJECTED -> {
        Answer answer = last == null ? null : last.payloadAs(AnswerPayload.class).answer();
        if (answer == null || answer == Answer.ACK) {
          throw new IllegalStateException("거절된 고친 내용에 보류·이견 응답이 없어요: " + row.getId());
        }
        yield AckState.valueOf(answer.name());
      }
      default -> AckState.PENDING;
    };
  }

  /** 고친 내용의 마지막 응답 행동. 없으면 null. */
  private ActionEntry lastResponse(CorrectionRequest row) {
    ActionEntry last = null;
    for (ActionEntry entry : actions.findByTarget(ActionTarget.correctionRequest(row.getId()))) {
      if (entry.type() == ActionType.RESPOND_CORRECTION) {
        last = entry;
      }
    }
    return last;
  }

  /**
   * 그 기록의 고친 내용 모두(만든 순서). 응답 종류·사유는 마지막 응답 행동에서 읽는다(사유는 보류·이견일 때만 보인다, decisions 2026-11-16).
   *
   * @param targetKey 기록의 대상 글자({@code CorrectionTargetKind.targetKey})
   */
  public List<CorrectionView> views(String targetKey) {
    List<CorrectionView> result = new ArrayList<>();
    for (CorrectionRequest row : corrections.findByTargetKeyOrderByCreatedAtAscIdAsc(targetKey)) {
      List<CorrectionView.Response> responses = new ArrayList<>();
      ActionEntry last = null;
      for (ActionEntry entry : actions.findByTarget(ActionTarget.correctionRequest(row.getId()))) {
        if (entry.type() == ActionType.RESPOND_CORRECTION) {
          Answer given = entry.payloadAs(AnswerPayload.class).answer();
          responses.add(
              new CorrectionView.Response(
                  AckState.valueOf(given.name()),
                  given == Answer.ACK ? null : entry.reason(),
                  entry.actorSide(),
                  entry.createdAt()));
          last = entry;
        }
      }
      AckState answer = answerOf(row, last);
      boolean objected = answer == AckState.NEED_MORE || answer == AckState.DISPUTE;
      result.add(
          new CorrectionView(
              row.getId(),
              row.getField(),
              valueOf(row),
              row.getRequestedBySide(),
              row.getReason(),
              row.getStatus(),
              answer,
              objected ? last.reason() : null,
              last == null ? null : last.createdAt(),
              row.getClosedReason(),
              row.getCreatedAt(),
              responses));
    }
    return List.copyOf(result);
  }

  private final class Responding implements StateStore<CorrectionState> {

    private final Correctable target;

    /** [모두 확인]일 때만: 그 기록의 대상 글자와 화면이 본 고친 내용들. 하나씩 응답이면 null. */
    private final String targetKey;

    private final Set<UUID> seenIds;

    /** 이 묶음에서 앞 단계가 맞아요로 올린 도장 판 수. */
    private int merged;

    private Responding(Correctable target, String targetKey, Set<UUID> seenIds) {
      this.target = target;
      this.targetKey = targetKey;
      this.seenIds = seenIds;
    }

    @Override
    public CorrectionState load(ActionCommand command) {
      CorrectionRequest row = row(command);
      if (targetKey != null) {
        checkSeen(row);
      }
      CorrectableSnapshot snapshot = snapshot(target, row.targetId(), command);
      Integer startNo = snapshot.confirmedNo() == null ? null : snapshot.confirmedNo() - merged;
      // 처리 완료(양측)된 기록이면 보류·이견이던 고친 내용도 맞아요로 바꾸지 못한다(앞모습이 바뀌므로, REP-7k·7l, #287 리뷰)
      return CorrectionState.forAnswering(
          row.getRequestedBySide(),
          row.getStatus(),
          row.getClosedReason() == ClosedReason.CLOSED_BY_BOTH || snapshot.closedByBoth(),
          answerOf(row),
          startNo);
    }

    private void checkSeen(CorrectionRequest row) {
      if (!row.getTargetKey().equals(targetKey)) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      if (merged > 0) {
        return;
      }
      Set<UUID> waiting = new HashSet<>();
      corrections
          .findByTargetKeyAndStatus(targetKey, CorrectionStatus.WAITING)
          .forEach(waitingRow -> waiting.add(waitingRow.getId()));
      if (!waiting.equals(seenIds)) {
        throw new BusinessException(ErrorCode.VERSION_CONFLICT);
      }
    }

    /**
     * 맞아요면 "지금 도장 판 + 이 칸"으로 새 판을 만들고(REP-7e) 이 칸만 바꾼다(다른 고친 내용은 그대로, REP-7i). 보류·이견이면 앞모습은 그대로이고
     * 처리 상태만 거절이다(REP-7m). 그 기록에 기다리는 고친 내용이 남지 않으면 고친 내용 확인 요청을 답함으로 닫는다(05 2-4). 늦은 답이면 열린 요청이 없어
     * notification이 아무것도 하지 않는다.
     */
    @Override
    public SavedState save(ActionCommand command, CorrectionState next) {
      CorrectionRequest row = row(command);
      if (next.answer() == AckState.ACK) {
        target.mergeConfirmed(row.targetId(), row.getBaseVersionId(), row.getField(), valueOf(row));
        row.accept();
        merged++;
      } else {
        row.reject();
      }
      corrections.saveAndFlush(row);
      if (!corrections.existsByTargetKeyAndStatus(row.getTargetKey(), CorrectionStatus.WAITING)) {
        waits.answered(
            recordRequest(row.targetKind(), row.targetId()),
            RequestKind.CORRECTION_CONFIRM,
            command.actor().side());
      }
      return SavedState.unchanged();
    }
  }

  private final class Withdrawing implements StateStore<CorrectionState> {

    @Override
    public CorrectionState load(ActionCommand command) {
      CorrectionRequest row = row(command);
      return CorrectionState.forAnswering(
          row.getRequestedBySide(), row.getStatus(), false, AckState.PENDING, null);
    }

    /**
     * 기다리던 고친 내용을 거둔다. 앞모습은 그대로다. 그 기록에 기다리는 고친 내용이 남지 않으면 같은 트랜잭션에서 고친 내용 확인 요청도 "거둠"으로 닫는다(상대가 답할
     * 것이 없는데 요청·재통지가 남지 않게, 03 G·05 2-4, #258 리뷰).
     */
    @Override
    public SavedState save(ActionCommand command, CorrectionState next) {
      CorrectionRequest row = row(command);
      row.withdraw();
      corrections.saveAndFlush(row);
      if (!corrections.existsByTargetKeyAndStatus(row.getTargetKey(), CorrectionStatus.WAITING)) {
        waits.withdrawn(
            recordRequest(row.targetKind(), row.targetId()),
            RequestKind.CORRECTION_CONFIRM,
            command.actor().side());
      }
      return SavedState.unchanged();
    }
  }
}
