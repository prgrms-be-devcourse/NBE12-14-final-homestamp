package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.correction.dto.AppliedVersion;
import com.chilsuitjo.back.correction.dto.CorrectableSnapshot;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.Correctable;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.repository.RepairContentRepository;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수리 건 내용(부위·제목·설명)의 고치기(03 G, 06 REP-7, 09 3장 "고치기"). {@code correction}이 이 구현을 종류({@code
 * REPAIR_CASE})로 골라 부른다.
 *
 * <p>판은 B 테이블이라 고치지 않고 새 판을 더한다(05 2-5 {@code repair_content}). 새 판은 지금 판 다음 번호이고 이전 판은 지금 판이다. 칸
 * 값의 모양: {@code roomPartId}는 부위 ID 글자, {@code title}·{@code description}은 글자(설명은 비울 수 있어 null). 사진은
 * 고치기가 없고 추가만 있다(03 G "사진").
 *
 * <p>처리 완료(양측 확인)된 수리 건의 내용은 고치지 않는다({@code CLOSED_BY_BOTH}, 06 REP-7k).
 */
@Component
@RequiredArgsConstructor
public class RepairCorrectable implements Correctable {

  static final String ROOM_PART_ID = "roomPartId";
  static final String TITLE = "title";
  static final String DESCRIPTION = "description";

  /** 하자 등록과 같은 길이(06 4-6, V1 {@code repair_content.title} 100). */
  static final int MAX_TITLE = 100;

  static final int MAX_DESCRIPTION = 2000;

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final RoomQueryService rooms;
  private final ActionRequestContext requestContext;

  @Override
  public CorrectionTargetKind kind() {
    return CorrectionTargetKind.REPAIR_CASE;
  }

  /** 앞모습은 도장 판이 있으면 도장 판, 없으면 지금 판이다. */
  @Override
  public CorrectableSnapshot load(UUID targetId) {
    RepairCase row = caseRow(targetId);
    RepairContent current = content(row.getCurrentContentId());
    RepairContent confirmed =
        row.getConfirmedContentId() == null ? null : content(row.getConfirmedContentId());
    return new CorrectableSnapshot(
        row.getRoomRecordId(),
        row.getCreatedBySide(),
        current.getId(),
        confirmed == null ? null : confirmed.getId(),
        current.getNo(),
        confirmed == null ? null : confirmed.getNo(),
        fieldsOf(confirmed == null ? current : confirmed),
        row.getStatus() == RepairStatus.DONE && row.getClosedBy() == ClosedBy.BOTH);
  }

  /**
   * 칸 값 확인. 없는 칸·글자가 아닌 값·빈 제목·길이 초과·틀린 부위 ID는 {@code VALIDATION_FAILED}, 남의 부위·없는 부위는 {@code
   * NOT_FOUND}, 숨긴 부위·꺼 둔 설비·가전은 새 기록을 받지 않아 {@code VALIDATION_FAILED}(하자 등록과 같음, 06 ROOM-3a·3d,
   * #182). 빈 설명은 null로 둔다.
   */
  @Override
  public Map<String, Object> normalize(UUID targetId, Map<String, Object> changes, UUID memberId) {
    RepairCase row = caseRow(targetId);
    Map<String, Object> normalized = new HashMap<>();
    for (Map.Entry<String, Object> change : changes.entrySet()) {
      Object value = change.getValue();
      if (value != null && !(value instanceof String)) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      String text = (String) value;
      switch (change.getKey()) {
        case ROOM_PART_ID -> normalized.put(ROOM_PART_ID, part(row, text, memberId).toString());
        case TITLE -> {
          if (text == null || text.isBlank() || text.length() > MAX_TITLE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
          }
          ResidentIdGuard.check(text);
          normalized.put(TITLE, text);
        }
        case DESCRIPTION -> {
          if (text != null && text.length() > MAX_DESCRIPTION) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
          }
          ResidentIdGuard.check(text);
          normalized.put(DESCRIPTION, text == null || text.isBlank() ? null : text);
        }
        default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
    }
    return normalized;
  }

  /** 지금 판에 칸들을 합친 새 판을 지금 판으로 한다(도장 판은 아직 없음, REP-7a). */
  @Override
  public AppliedVersion applyNow(UUID targetId, Map<String, Object> changes, PartySide bySide) {
    RepairCase row = caseRow(targetId);
    RepairContent current = content(row.getCurrentContentId());
    Map<String, Object> next = fieldsOf(current);
    next.putAll(changes);
    RepairContent added = save(row, current, next, bySide);
    row.moveCurrent(added.getId());
    cases.flush();
    return applied(added);
  }

  /**
   * 도장 판에 이 칸만 합친 새 판을 지금 판·도장 판으로 한다(REP-7e·7i). 기준 판 뒤 도장 판에서 같은 칸이 바뀌었으면 합치지 않고 {@code
   * VERSION_CONFLICT}다. 새 판을 만든 측은 원래 기록한 측이다(고친 사람).
   */
  @Override
  public AppliedVersion mergeConfirmed(
      UUID targetId, UUID baseVersionId, String field, Object newValue) {
    RepairCase row = caseRow(targetId);
    if (row.getConfirmedContentId() == null) {
      throw new IllegalStateException("도장 판이 없는 수리 건에 고친 내용이 있어요: " + targetId);
    }
    RepairContent confirmed = content(row.getConfirmedContentId());
    Map<String, Object> next = fieldsOf(confirmed);
    if (!confirmed.getId().equals(baseVersionId)
        && !Objects.equals(fieldsOf(content(baseVersionId)).get(field), next.get(field))) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    next.put(field, newValue);
    RepairContent added =
        save(row, content(row.getCurrentContentId()), next, row.getCreatedBySide());
    row.moveCurrentAndConfirmed(added.getId());
    cases.flush();
    return applied(added);
  }

  @Override
  public DigestInput digestOf(UUID versionId) {
    return RepairStateStore.digest(content(versionId));
  }

  /** [사진 더해서 다시 요청]은 다음 PR(#258)에서 만든다. 지금은 부르는 곳이 없다. */
  @Override
  public void attachEvidence(UUID targetId, UUID correctionRequestId, List<String> uploadTickets) {
    throw new UnsupportedOperationException("고친 내용 다시 요청은 아직 만들지 않았어요(#258 후속)");
  }

  /** 새 판을 지금 판 다음 번호로 저장한다. 시각은 행동 시각이다. */
  private RepairContent save(
      RepairCase row, RepairContent current, Map<String, Object> fields, PartySide bySide) {
    RepairContent added =
        new RepairContent(
            Uuids.v7(),
            row.getRoomRecordId(),
            row.getId(),
            current.getNo() + 1,
            UUID.fromString((String) fields.get(ROOM_PART_ID)),
            (String) fields.get(TITLE),
            (String) fields.get(DESCRIPTION),
            current.getId(),
            bySide,
            requestContext.at());
    return contents.save(added);
  }

  private UUID part(RepairCase row, String text, UUID memberId) {
    UUID partId;
    try {
      partId = UUID.fromString(Objects.requireNonNull(text));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    // 숨긴 부위·꺼 둔 설비·가전은 새 기록을 받지 않는다(하자 등록과 같음, 06 ROOM-3a·3d, #182)
    RoomPartSummary seen = rooms.findPartAsSeenBy(row.getRoomRecordId(), partId, memberId);
    if (seen.hidden() || !seen.enabled()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return partId;
  }

  private static AppliedVersion applied(RepairContent content) {
    return new AppliedVersion(content.getId(), content.getNo(), RepairStateStore.digest(content));
  }

  /** 판의 칸 값({@link #normalize}가 돌려주는 모양과 같음). 설명이 없으면 null이 들어간다. */
  private static Map<String, Object> fieldsOf(RepairContent content) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(ROOM_PART_ID, content.getRoomPartId().toString());
    fields.put(TITLE, content.getTitle());
    fields.put(DESCRIPTION, content.getDescription());
    return fields;
  }

  private RepairCase caseRow(UUID repairCaseId) {
    return cases
        .findById(repairCaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private RepairContent content(UUID contentId) {
    return contents
        .findById(contentId)
        .orElseThrow(() -> new IllegalStateException("판이 없어요: " + contentId));
  }
}
