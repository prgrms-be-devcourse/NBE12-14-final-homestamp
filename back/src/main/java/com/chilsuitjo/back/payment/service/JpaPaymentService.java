package com.chilsuitjo.back.payment.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.payment.dto.PaymentCreateRequest;
import com.chilsuitjo.back.payment.dto.PaymentRecordResult;
import com.chilsuitjo.back.payment.repository.PaymentRepository;
import com.chilsuitjo.back.payment.repository.PaymentStateStore;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import com.chilsuitjo.back.payment.rule.PaymentState;
import com.chilsuitjo.back.payment.rule.PaymentTransitions;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 지불 행동을 pipeline에 넘긴다(06 MONEY-1·2). 트랜잭션은 pipeline이 연다(09 3-1): {@code @Transactional}을 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class JpaPaymentService implements PaymentService {

  /** 제3자 관계 설명 길이(V1 {@code payment_version.payer_label}·{@code payee_label}). */
  static final int MAX_LABEL = 30;

  /** 증빙 종류 길이(V1 {@code payment_version.evidence_kind}). */
  static final int MAX_EVIDENCE_KIND = 30;

  /** 한 번에 붙이는 증빙 사진 수(하자 보충·원인 설명과 같음, 06 4-5). */
  static final int MAX_PHOTOS = 10;

  private final ActionPipeline pipeline;
  private final PaymentStateStore store;
  private final PaymentTransitions rules;
  private final PaymentRepository payments;

  /**
   * {@inheritDoc}
   *
   * <p>pipeline 전에 입력을 본다({@link #validate}). 그 뒤 같은 수리 건·방향·금액의 지불이 있으면 {@code
   * DUPLICATE_PAYMENT}다: 상대의 확인 전 기록이면 {@code similar = false}(MONEY-1a), 30일 안 비슷한 기록이고 {@code
   * similarConfirmed}가 아니면 {@code similar = true}(MONEY-1c). 상대가 연결된 회원이면 금액 확인 요청을 연다(03 F. 연결 전이면
   * 요청 없이 혼자 기록, 불변식 20·06 LINK-1e).
   */
  @Override
  public PaymentRecordResult record(
      RoomAccess access, String idempotencyKey, UUID repairCaseId, PaymentCreateRequest req) {
    validate(req);
    Map<String, Object> payload =
        req.isSimilarConfirmed()
            ? Map.of(PaymentTransitions.RecordPayload.SIMILAR_CONFIRMED, true)
            : Map.of();
    PipelineResult<PaymentState> result =
        pipeline.create(
            new ActionCommand(
                access.roomRecordId(),
                Actor.member(access.memberId()),
                ActionType.RECORD_PAYMENT,
                idempotencyKey,
                null,
                null,
                null,
                null,
                payload),
            store.recording(repairCaseId, req),
            rules);
    return new PaymentRecordResult(result.action().target().id(), result.action().replayed());
  }

  /**
   * {@inheritDoc}
   *
   * <p>답이 없으면 {@code VALIDATION_FAILED}, 사유에 주민등록번호 형태가 있으면 {@code RESIDENT_ID_BLOCKED}다(pipeline
   * 전, 03 6-1. 사유는 고칠 수 없는 행동 기록에 남는다). 답은 payload {@code answer}, 사유는 행동 기록의 {@code reason}이고 내용
   * 지문은 본 판이다(안내서 3-3).
   */
  @Override
  public AnswerResult respond(
      UUID paymentId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.reason());
    PipelineResult<PaymentState> result =
        pipeline.execute(
            new ActionCommand(
                store.roomOf(paymentId),
                Actor.member(memberId),
                ActionType.RESPOND_AMOUNT,
                idempotencyKey,
                ActionTarget.payment(paymentId),
                request.seenVersionNo(),
                store.seenContent(paymentId, request.seenVersionNo()).orElse(null),
                request.reason() == null || request.reason().isBlank() ? null : request.reason(),
                Map.of("answer", request.answer().name())),
            store.responding(),
            rules);
    return new AnswerResult(
        paymentId,
        request.seenVersionNo(),
        AckState.valueOf(result.state().response().name()),
        result.action().replayed());
  }

  @Override
  public boolean hasMoney(UUID roomRecordId, UUID repairCaseId) {
    return payments.existsByRoomRecordIdAndRepairCaseId(roomRecordId, repairCaseId);
  }

  @Override
  public long confirmedTotal(UUID roomRecordId, UUID repairCaseId) {
    return payments.sumConfirmed(roomRecordId, repairCaseId);
  }

  /**
   * 낸 사람·받는 사람의 종류·금액(0 이상)·날짜가 없거나, 관계 설명이 제3자가 아닌데 있거나 제3자인데 비었거나 30자를 넘거나, 증빙 종류가 30자를 넘거나, 사진이
   * 10장을 넘거나 빈 표가 있으면 {@code VALIDATION_FAILED}. 글에 주민등록번호 형태가 있으면 {@code RESIDENT_ID_BLOCKED}(03
   * 6-1).
   */
  static void validate(PaymentCreateRequest req) {
    if (req == null
        || req.payer() == null
        || req.payer().kind() == null
        || req.payee() == null
        || req.payee().kind() == null
        || req.amount() == null
        || req.amount() < 0
        || req.paidOn() == null
        || !labelFits(req.payer().kind() == PayerKind.THIRD_PARTY, req.payer().label())
        || !labelFits(req.payee().kind() == PayeeKind.THIRD_PARTY, req.payee().label())
        || (req.evidenceKind() != null && req.evidenceKind().length() > MAX_EVIDENCE_KIND)
        || !ticketsFit(req.uploadTickets())) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(req.payer().label(), req.payee().label(), req.evidenceKind());
  }

  /** 제3자면 1~30자 관계 설명이 있어야 하고, 아니면 없어야 한다(MONEY-1b). */
  private static boolean labelFits(boolean thirdParty, String label) {
    if (!thirdParty) {
      return label == null;
    }
    return label != null && !label.isBlank() && label.length() <= MAX_LABEL;
  }

  private static boolean ticketsFit(List<String> tickets) {
    return tickets == null
        || (tickets.size() <= MAX_PHOTOS
            && tickets.stream().noneMatch(t -> t == null || t.isBlank()));
  }
}
