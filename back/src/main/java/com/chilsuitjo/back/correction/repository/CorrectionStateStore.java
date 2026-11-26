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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    return new Responding(target);
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
    return switch (row.getStatus()) {
      case ACCEPTED -> AckState.ACK;
      case REJECTED -> {
        Answer last = null;
        for (ActionEntry entry :
            actions.findByTarget(ActionTarget.correctionRequest(row.getId()))) {
          if (entry.type() == ActionType.RESPOND_CORRECTION) {
            last = entry.payloadAs(AnswerPayload.class).answer();
          }
        }
        if (last == null || last == Answer.ACK) {
          throw new IllegalStateException("거절된 고친 내용에 보류·이견 응답이 없어요: " + row.getId());
        }
        yield AckState.valueOf(last.name());
      }
      default -> AckState.PENDING;
    };
  }

  private final class Responding implements StateStore<CorrectionState> {

    private final Correctable target;

    private Responding(Correctable target) {
      this.target = target;
    }

    @Override
    public CorrectionState load(ActionCommand command) {
      CorrectionRequest row = row(command);
      CorrectableSnapshot snapshot = snapshot(target, row.targetId(), command);
      return CorrectionState.forAnswering(
          row.getRequestedBySide(),
          row.getStatus(),
          row.getClosedReason() == ClosedReason.CLOSED_BY_BOTH,
          answerOf(row),
          snapshot.confirmedNo());
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
     * 기다리던 고친 내용을 거둔다. 앞모습은 그대로다.
     *
     * <p>한계: 그 기록에 기다리는 고친 내용이 0개가 돼도 고친 내용 확인 요청은 열린 채로 남는다. 요청을 보낸 측이 거둘 때 닫는 창구가 {@code
     * WaitService}에 아직 없다({@code answered}는 보낸 측이면 아무것도 하지 않고, {@code close}에는 맞는 닫힌 이유가 없음, #258).
     */
    @Override
    public SavedState save(ActionCommand command, CorrectionState next) {
      CorrectionRequest row = row(command);
      row.withdraw();
      corrections.saveAndFlush(row);
      return SavedState.unchanged();
    }
  }
}
