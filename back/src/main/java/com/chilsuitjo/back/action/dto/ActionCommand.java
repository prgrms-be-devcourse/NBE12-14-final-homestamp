package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import java.util.Map;
import java.util.UUID;

/**
 * 행동 한 줄을 쓰는 데 필요한 값. 행동이 하나인 요청은 부르는 쪽이 만들고, 여러 행동이면 pipeline이 단계마다 만든다(09 3-1).
 *
 * @param roomRecordId 호실 기록. 호실을 만드는 요청({@code ActionBatch.newRoom})의 첫 단계는 저장 전까지 null
 * @param type 행동 종류. 예: {@code ActionType.START_REPAIR}
 * @param idempotencyKey 화면의 {@code Idempotency-Key}. 한 요청에 행동이 여럿이면 pipeline이 {@code <키>#2} …로 채운다
 * @param target 대상. 호실 기록 자체거나 아직 없는 것을 만드는 행동(예: {@code REGISTER})이면 null. 만드는 행동은 저장 뒤 {@link
 *     SavedState#target()}으로 채워진다
 * @param seenVersionNo 확인할 때 본 판의 no(05 {@code action.seen_version_no}). {@code CONFIRM_FINISH}면
 *     {@code settlement.snapshot_no}. 없으면 null
 * @param content 그때 본 판의 지문 재료(05 {@code action.content_digest}). 내용 대상이 없는 행동만 null
 * @param payload 작은 부가 값만. 예: {@code {"answer": ACK}}. {@code apply}는 {@link
 *     ActionEntry#payloadAs}로 읽는다
 */
public record ActionCommand(
    UUID roomRecordId,
    Actor actor,
    ActionType type,
    String idempotencyKey,
    ActionTarget target,
    Integer seenVersionNo,
    DigestInput content,
    String reason,
    Map<String, Object> payload) {

  public ActionCommand {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public ActionCommand withRoomRecordId(UUID newRoomRecordId) {
    return new ActionCommand(
        newRoomRecordId,
        actor,
        type,
        idempotencyKey,
        target,
        seenVersionNo,
        content,
        reason,
        payload);
  }

  public ActionCommand withActor(Actor newActor) {
    return new ActionCommand(
        roomRecordId,
        newActor,
        type,
        idempotencyKey,
        target,
        seenVersionNo,
        content,
        reason,
        payload);
  }

  public ActionCommand withTarget(ActionTarget newTarget) {
    return new ActionCommand(
        roomRecordId,
        actor,
        type,
        idempotencyKey,
        newTarget,
        seenVersionNo,
        content,
        reason,
        payload);
  }

  public ActionCommand withContent(DigestInput newContent) {
    return new ActionCommand(
        roomRecordId,
        actor,
        type,
        idempotencyKey,
        target,
        seenVersionNo,
        newContent,
        reason,
        payload);
  }
}
