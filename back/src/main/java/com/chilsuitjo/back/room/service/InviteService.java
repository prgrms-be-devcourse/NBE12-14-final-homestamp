package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.BatchResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.PhoneNumber;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.room.dto.CounterpartPhoneRequest;
import com.chilsuitjo.back.room.dto.InviteRequest;
import com.chilsuitjo.back.room.dto.Invited;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.PartyStateStore;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.ConsentVersion;
import com.chilsuitjo.back.room.rule.PartyTransitions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 상대 초대(06 ROOM-4, CONS-2, 4-3 {@code POST /api/rooms/{id}/invite}, #163).
 *
 * <p>한 요청에 공유 동의({@code SHARE_CONSENT}, payload {@code consentVersion})와 초대({@code INVITE})를 남기고,
 * 자리·링크·문자를 같은 트랜잭션에 넣는다(06 ROOM-4c, 09 3-1 초대 예시). 트랜잭션은 pipeline이 연다(09 3-1). 그래서 여기에
 * {@code @Transactional}을 두지 않는다.
 *
 * <p>번호: 번호만({@code NUMBER_ONLY}) 자리는 번호를 비워 보내면 호실을 만들 때 저장한 번호로 초대하고, 새 번호를 넣으면 그 번호로 덮어쓴다(03 B
 * "상대 초대하기", #407 리뷰). 비어 있음 자리에 번호가 없으면 {@code VALIDATION_FAILED}다.
 *
 * <p>오류: 판이 없으면 {@code CONSENT_REQUIRED}, 번호·기록 모양이 틀리면 {@code VALIDATION_FAILED}(여기서 먼저), 당사자가 아니면
 * {@code NOT_FOUND}, 정산 완료·합류 전 기록·공유 중단이면 pipeline 거절, 만든 측이 아니거나 거주 종료이거나 비어 있음·번호만 자리가 아니면
 * {@code INVALID_STATE}, 내 인증 번호면 {@code SELF_INVITE}, 수신 거부 목록에 있는 번호면 {@code
 * PHONE_OPTED_OUT}({@code PartyTransitions}).
 *
 * <p>아직 하지 않는 것(#163): 기록 링크의 확인 요청 열기(#163 D 추천의 D 메서드·inspection 읽기 포트 뒤. 그전까지 점검 확인 요청은 점검의 확인
 * 요청 보내기가 연결 전 상대에게 연다).
 */
@Service
@RequiredArgsConstructor
public class InviteService {

  private final ActionPipeline pipeline;
  private final RoomRecordRepository rooms;
  private final PartyRepository parties;
  private final PartyStateStore store;
  private final PartyTransitions rules;

  public Invited.Result invite(
      UUID roomRecordId, UUID memberId, String idempotencyKey, InviteRequest request) {
    String consentVersion = ConsentVersion.require(request.consentVersion());
    String phone = optionalPhone(request.phone());
    RequestTarget record = sharedRecord(request.record());
    UUID partyId = counterpartSeat(roomRecordId);

    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                .record(ActionType.SHARE_CONSENT, Map.of("consentVersion", consentVersion))
                .step(
                    ActionType.INVITE,
                    done -> ActionTarget.party(partyId),
                    store.inviting(phone, record),
                    rules));
    Party party = parties.findById(partyId).orElseThrow();
    return new Invited.Result(new Invited(partyId, party.getStatus()), result.replayed());
  }

  /**
   * 상대 번호 고쳐 다시 초대(06 4-3 {@code POST /api/rooms/{id}/counterpart-phone}, ROOM-4·LINK-4, #437).
   * 초대됨·거절됨 자리만 받는다. 받는 사람이 바뀌어 초대처럼 공유 동의({@code SHARE_CONSENT})를 다시 남기고, 번호 고치기({@code
   * CHANGE_INVITE_PHONE})는 번호 값 없이 바꾼 사실만 남긴다(05 1-3, 04 "개인정보 로그"). 저장 순서는 {@code
   * PartyStateStore.changingPhone}.
   *
   * <p>오류: 판이 없으면 {@code CONSENT_REQUIRED}, 번호가 없거나 모양이 틀리면 {@code VALIDATION_FAILED}(여기서 먼저), 당사자가
   * 아니면 {@code NOT_FOUND}, 정산 완료·합류 전 기록·공유 중단이면 pipeline 거절, 지금 초대 번호와 같으면 {@code
   * VALIDATION_FAILED}, 만든 측이 아니거나 거주 종료이거나 초대됨·거절됨 자리가 아니면 {@code INVALID_STATE}, 내 인증 번호면 {@code
   * SELF_INVITE}, 수신 거부 목록에 있는 번호면 {@code PHONE_OPTED_OUT}.
   *
   * <p>호실 상세 {@code availableActions}에는 아직 넣지 않는다. 초대 링크를 새로 발급하므로 상대 초대와 같이 #370 활성화 조건이 채워진 뒤
   * 더한다.
   */
  public Invited.Result changePhone(
      UUID roomRecordId, UUID memberId, String idempotencyKey, CounterpartPhoneRequest request) {
    String consentVersion =
        ConsentVersion.require(request == null ? null : request.consentVersion());
    String phone = optionalPhone(request.phone());
    if (phone == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    UUID partyId = counterpartSeat(roomRecordId);

    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                .record(ActionType.SHARE_CONSENT, Map.of("consentVersion", consentVersion))
                .step(
                    ActionType.CHANGE_INVITE_PHONE,
                    done -> ActionTarget.party(partyId),
                    store.changingPhone(phone),
                    rules));
    Party party = parties.findById(partyId).orElseThrow();
    return new Invited.Result(new Invited(partyId, party.getStatus()), result.replayed());
  }

  /**
   * 초대할 자리: 호실을 만든 측의 반대편. 잠그기 전에 읽지만 한 호실의 두 자리는 호실을 만들 때 생기고 측이 바뀌지 않아 안전하다. 당사자인지·만든 측인지는 잠근 뒤에
   * 본다(pipeline, {@code PartyStateStore.load}).
   */
  private UUID counterpartSeat(UUID roomRecordId) {
    RoomRecord room =
        rooms.findById(roomRecordId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    PartySide other =
        room.getCreatedBySide() == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
    return parties
        .findByRoomRecordIdAndSide(roomRecordId, other)
        .map(Party::getId)
        .orElseThrow(() -> new IllegalStateException("호실의 상대 자리가 없어요: " + roomRecordId));
  }

  /**
   * 새로 넣은 번호. 비어 있으면 {@code null}이고, 그때 어느 번호로 초대할지(번호만 자리의 저장한 번호, 비어 있음 자리면 {@code
   * VALIDATION_FAILED})는 자리를 잠근 뒤 {@code PartyStateStore}가 정한다. 넣었으면 모양이 틀릴 때 여기서 먼저 {@code
   * VALIDATION_FAILED}다.
   */
  private static String optionalPhone(String input) {
    return input == null || input.isBlank() ? null : PhoneNumber.normalize(input);
  }

  /** 기록 링크의 기록. 종류·ID가 함께 있어야 하고 지금은 점검만 받는다. */
  private static RequestTarget sharedRecord(InviteRequest.SharedRecord record) {
    if (record == null) {
      return null;
    }
    if (record.kind() != TargetKind.INSPECTION || record.id() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return RequestTarget.of(record.kind(), record.id());
  }
}
