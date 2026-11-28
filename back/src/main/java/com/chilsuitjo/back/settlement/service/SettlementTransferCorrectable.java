package com.chilsuitjo.back.settlement.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.correction.dto.AppliedVersion;
import com.chilsuitjo.back.correction.dto.CorrectableSnapshot;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.Correctable;
import com.chilsuitjo.back.correction.service.CorrectionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.settlement.entity.SettlementTransfer;
import com.chilsuitjo.back.settlement.entity.SettlementTransferVersion;
import com.chilsuitjo.back.settlement.repository.SettlementChanges;
import com.chilsuitjo.back.settlement.repository.SettlementTransferRepository;
import com.chilsuitjo.back.settlement.repository.SettlementTransferVersionRepository;
import com.chilsuitjo.back.settlement.repository.TransferStateStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산 송금의 금액·날짜 고치기(03 J "금액·날짜 고치기", 06 SET-4c·4d, {@code POST /api/corrections}). 상대가 수령 확인(맞아요)하기
 * 전이면 그냥 고쳐지고, 확인 뒤면 상대가 고친 내용을 확인해야 바뀐다. 도장은 맞아요를 받은 판이다({@code confirmed_version_id}).
 *
 * <p>판이 바뀌면 같은 트랜잭션에서 정산 모습 번호를 올린다(05 2-7, 불변식 19).
 *
 * <p>확인 뒤 고친 내용을 요청·다시 요청하거나 상대가 거절해도(판은 그대로) 정산이 바뀐 것으로 본다({@link #pendingChanged}).
 *
 * <p><b>현재 구현 범위의 한계</b>: 고친 내용을 거두면({@code withdraw}) 정산 모습 번호를 올리지 않는다(거두기 저장에는 대상 기록 창구가 없음).
 * 송금에는 사진이 붙지 않아 다시 요청의 근거 사진은 받지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SettlementTransferCorrectable implements Correctable {

  static final String AMOUNT = "amount";
  static final String PAID_ON = "paidOn";

  private final SettlementTransferRepository transfers;
  private final SettlementTransferVersionRepository versions;
  private final SettlementChanges changes;
  private final CorrectionReader corrections;
  private final ActionRequestContext requestContext;

  @Override
  public CorrectionTargetKind kind() {
    return CorrectionTargetKind.SETTLEMENT_TRANSFER;
  }

  /** 앞모습은 도장 판(상대가 맞아요를 누른 판)이 있으면 도장 판, 없으면 지금 판이다. */
  @Override
  public CorrectableSnapshot load(UUID targetId) {
    SettlementTransfer row = row(targetId);
    SettlementTransferVersion current = version(row.getCurrentVersionId());
    SettlementTransferVersion confirmed =
        row.getConfirmedVersionId() == null ? null : version(row.getConfirmedVersionId());
    return new CorrectableSnapshot(
        row.getRoomRecordId(),
        row.getRecordedBySide(),
        current.getId(),
        confirmed == null ? null : confirmed.getId(),
        current.getNo(),
        confirmed == null ? null : confirmed.getNo(),
        fieldsOf(confirmed == null ? current : confirmed),
        false);
  }

  /** 칸 값 확인. 금액은 1 이상의 정수, 날짜는 {@code yyyy-MM-dd}다. 없는 칸·틀린 형식은 {@code VALIDATION_FAILED}. */
  @Override
  public Map<String, Object> normalize(UUID targetId, Map<String, Object> changes, UUID memberId) {
    row(targetId);
    Map<String, Object> normalized = new HashMap<>();
    for (Map.Entry<String, Object> change : changes.entrySet()) {
      Object value = change.getValue();
      switch (change.getKey()) {
        case AMOUNT -> normalized.put(AMOUNT, amount(value));
        case PAID_ON -> normalized.put(PAID_ON, paidOn(value).toString());
        default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
    }
    return normalized;
  }

  /** 지금 판에 칸들을 합친 새 판을 지금 판으로 한다(도장 전, SET-4c). 수령 확인 요청은 같은 송금이라 그대로 이어진다. */
  @Override
  public AppliedVersion applyNow(UUID targetId, Map<String, Object> changed, PartySide bySide) {
    SettlementTransfer row = row(targetId);
    SettlementTransferVersion current = version(row.getCurrentVersionId());
    Map<String, Object> next = fieldsOf(current);
    next.putAll(changed);
    SettlementTransferVersion added = save(row, current, next);
    row.moveCurrent(added.getId());
    transfers.flush();
    changes.changed(row.getSettlementId());
    return applied(added);
  }

  /**
   * 도장 판에 이 칸만 합친 새 판을 지금 판·도장 판으로 한다(SET-4d). 기준 판 뒤 도장 판에서 같은 칸이 바뀌었으면 합치지 않고 {@code
   * VERSION_CONFLICT}다.
   */
  @Override
  public AppliedVersion mergeConfirmed(
      UUID targetId, UUID baseVersionId, String field, Object newValue) {
    SettlementTransfer row = row(targetId);
    if (row.getConfirmedVersionId() == null) {
      throw new IllegalStateException("도장 판이 없는 송금에 고친 내용이 있어요: " + targetId);
    }
    SettlementTransferVersion confirmed = version(row.getConfirmedVersionId());
    Map<String, Object> next = fieldsOf(confirmed);
    if (!confirmed.getId().equals(baseVersionId)
        && !Objects.equals(fieldsOf(version(baseVersionId)).get(field), next.get(field))) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    next.put(field, newValue);
    SettlementTransferVersion added = save(row, version(row.getCurrentVersionId()), next);
    row.moveCurrentAndConfirmed(added.getId());
    transfers.flush();
    changes.changed(row.getSettlementId());
    return applied(added);
  }

  /**
   * 확인 뒤 고친 내용을 요청·다시 요청했거나 상대가 거절했다. 판은 그대로지만 정산 내용이 바뀐 것이라 정산 모습 번호를 올리고 기다리던 끝내기 요청을 닫는다(06
   * SET-5b, 불변식 19).
   */
  @Override
  public void pendingChanged(UUID targetId) {
    changes.changed(row(targetId).getSettlementId());
  }

  @Override
  public DigestInput digestOf(UUID versionId) {
    return TransferStateStore.digest(version(versionId));
  }

  /** 송금에는 보충이 없어 고친 내용의 다시 요청만 센다. */
  @Override
  public Instant lastRerequestAt(UUID targetId) {
    return corrections.lastResubmitAt(CorrectionTargetKind.SETTLEMENT_TRANSFER, targetId);
  }

  /** 송금에는 사진이 붙지 않는다(05 {@code photo} 대상 칸에 송금이 없음). 표를 보내면 {@code VALIDATION_FAILED}. */
  @Override
  public List<PhotoDigest> attachEvidence(
      ActionCommand command, UUID targetId, UUID correctionRequestId, List<String> uploadTickets) {
    if (uploadTickets != null && !uploadTickets.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return List.of();
  }

  private SettlementTransferVersion save(
      SettlementTransfer row, SettlementTransferVersion current, Map<String, Object> fields) {
    return versions.save(
        new SettlementTransferVersion(
            Uuids.v7(),
            row.getRoomRecordId(),
            row.getId(),
            current.getNo() + 1,
            // 고친 내용의 새 값은 jsonb에서 다시 읽으면 Integer일 수 있다
            ((Number) fields.get(AMOUNT)).longValue(),
            LocalDate.parse((String) fields.get(PAID_ON)),
            current.getId(),
            requestContext.at()));
  }

  private static long amount(Object value) {
    if (!(value instanceof Number number)
        || number.doubleValue() != Math.rint(number.doubleValue())
        || number.longValue() < 1) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return number.longValue();
  }

  private static LocalDate paidOn(Object value) {
    if (!(value instanceof String text)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
  }

  private static AppliedVersion applied(SettlementTransferVersion version) {
    return new AppliedVersion(version.getId(), version.getNo(), TransferStateStore.digest(version));
  }

  /** 판의 칸 값({@link #normalize}가 돌려주는 모양과 같음: 금액은 {@code Long}, 날짜는 글자). */
  private static Map<String, Object> fieldsOf(SettlementTransferVersion version) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(AMOUNT, version.getAmount());
    fields.put(PAID_ON, version.getPaidOn().toString());
    return fields;
  }

  private SettlementTransfer row(UUID transferId) {
    return transfers
        .findById(transferId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private SettlementTransferVersion version(UUID versionId) {
    return versions
        .findById(versionId)
        .orElseThrow(() -> new IllegalStateException("송금 판이 없어요: " + versionId));
  }
}
