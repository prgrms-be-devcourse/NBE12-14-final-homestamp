package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.BatchResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.ConnectRequest;
import com.chilsuitjo.back.room.dto.InvitationAnswered;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.PartyStateStore;
import com.chilsuitjo.back.room.rule.ConsentVersion;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.PartyTransitions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 받은 초대에 답하기: 연결과 "제 집이 아니에요"(06 ROOM-5, CONS-2, 4-3 {@code connect}·{@code reject}, #163).
 *
 * <p>아직 당사자가 아닌 회원이라 pipeline을 초대 자리로 연다({@code ActionBatch.asInvitee}, decisions 2026-11-26,
 * #251). 그 자리가 이 호실의 것인지만 잠금에서 보고, 자리 상태와 번호는 {@code PartyTransitions}가 잠근 뒤 본다. 트랜잭션은 pipeline이
 * 연다(09 3-1).
 *
 * <p>오류: 판이 없으면 {@code CONSENT_REQUIRED}(연결만, 여기서 먼저), 자리가 없으면 {@code NOT_FOUND}, 정산 완료·합류 전 기록이면
 * pipeline 거절, 초대됨이 아니거나 이 호실의 다른 자리에 이미 있으면 {@code INVALID_STATE}, 인증 번호가 초대 번호와 다르면 {@code
 * PHONE_MISMATCH}. 동의 문구 판은 입력 검사라 자리 조회·잠금보다 먼저 본다(같은 키로 다시 와도 판이 없으면 거절).
 *
 * <p>아직 하지 않는 것(#163 범위 밖): 같은 집 기록 합류(ROOM-6, {@code JOIN_NOT_ALLOWED}), 초대한 측 서비스 안 알림(LINK-1g·03
 * B, D의 실제 알림 뒤), 쌓인 기록의 확인 요청 모음과 {@code first_shown_at}(ROOM-5a, 1-4), 연결할 때 정산 초대 링크 닫기(정산 초대 링크
 * LINK-3을 만들 때 더함, 03 C).
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

  private final ActionPipeline pipeline;
  private final PartyRepository parties;
  private final PartyStateStore store;
  private final PartyTransitions rules;

  /**
   * 연결([동의하고 연결하기]). 한 요청에 {@code CONNECT}와 {@code SHARE_CONSENT}(payload {@code consentVersion})를
   * 남긴다(09 3-1 {@code asInvitee} 예시).
   */
  public InvitationAnswered.Result connect(
      UUID partyId, UUID memberId, String idempotencyKey, ConnectRequest request) {
    String consentVersion =
        ConsentVersion.require(request == null ? null : request.consentVersion());
    UUID roomRecordId = roomOf(partyId);
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                .asInvitee(partyId)
                .step(
                    ActionType.CONNECT,
                    done -> ActionTarget.party(partyId),
                    store.connecting(),
                    rules)
                .record(ActionType.SHARE_CONSENT, Map.of("consentVersion", consentVersion)));
    return answered(roomRecordId, partyId, result);
  }

  /** "제 집이 아니에요"({@code REJECT_INVITE}). 공유 동의는 남기지 않는다. */
  public InvitationAnswered.Result reject(UUID partyId, UUID memberId, String idempotencyKey) {
    UUID roomRecordId = roomOf(partyId);
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                .asInvitee(partyId)
                .step(
                    ActionType.REJECT_INVITE,
                    done -> ActionTarget.party(partyId),
                    store.rejecting(),
                    rules));
    return answered(roomRecordId, partyId, result);
  }

  /**
   * 자리의 호실. 잠그기 전에 읽지만 자리가 속한 호실은 바뀌지 않고, pipeline이 잠근 뒤 그 호실의 자리인지 다시 본다({@code
   * lockAndReadAsInvitee}). 없는 자리면 {@code NOT_FOUND}.
   */
  private UUID roomOf(UUID partyId) {
    return parties
        .findById(partyId)
        .map(Party::getRoomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /** 연결됐으면 호실 기록 ID를 주고, 아니면 주지 않는다(연결 전에는 호실 기록 ID를 알려 주지 않음, 06 4-3 {@code invitations}). */
  private InvitationAnswered.Result answered(UUID roomRecordId, UUID partyId, BatchResult result) {
    Party party = parties.findById(partyId).orElseThrow();
    boolean connected = party.getStatus() == PartyStatus.CONNECTED;
    return new InvitationAnswered.Result(
        new InvitationAnswered(connected ? roomRecordId : null, partyId, party.getStatus()),
        result.replayed());
  }
}
