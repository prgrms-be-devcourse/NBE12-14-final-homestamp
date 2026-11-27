package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionResult;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.global.common.PhoneNumber;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.service.MemberQueryService;
import com.chilsuitjo.back.room.dto.CreateRoomRequest;
import com.chilsuitjo.back.room.dto.RoomCreated;
import com.chilsuitjo.back.room.repository.NewRoomStateStore;
import com.chilsuitjo.back.room.repository.PartTemplateRepository;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RegionRepository;
import com.chilsuitjo.back.room.repository.RoomPartRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.RoomTransitions;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 호실 기록 만들기(06 ROOM-1, 4-3 {@code POST /api/rooms}). 트랜잭션은 pipeline이 연다(09 3-1). 그래서 여기에
 * {@code @Transactional}을 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RoomService {

  private final ActionPipeline pipeline;
  private final ActionRequestContext requestContext;
  private final RoomTransitions transitions;
  private final RegionRepository regions;
  private final RoomRecordRepository rooms;
  private final PartyRepository parties;
  private final RoomPartRepository parts;
  private final PartTemplateRepository templates;
  private final MemberQueryService members;

  /**
   * 거주 중인 호실 기록, 내 당사자 자리와 반대쪽 자리, 기본 부위를 만들고 행동 {@code CREATE_ROOM}을 남긴다(06 ROOM-1a). 모두 한 트랜잭션이다.
   *
   * <p>상대 번호를 넣으면 반대쪽 자리는 번호만({@code NUMBER_ONLY})이고, 초대·링크·문자는 만들지 않는다(03 B, 첫 문자는 초대할 때). 번호는 행동
   * 기록 payload에 넣지 않는다(04 "개인정보 로그"). 수신 거부 목록은 문자를 보내는 초대 때 본다({@code PHONE_OPTED_OUT}, 06 4-3
   * {@code invite}).
   *
   * @throws BusinessException {@code VALIDATION_FAILED}(코드표에 없거나 고를 수 없는 법정동, 휴대폰 번호가 아닌 상대 번호, 잘못된
   *     {@code Idempotency-Key}), {@code SELF_INVITE}(상대 번호가 내 인증 번호, 03 3장 "자기 번호로 초대"), {@code
   *     IDEMPOTENCY_KEY_REUSED}
   */
  public RoomCreated create(UUID memberId, String idempotencyKey, CreateRoomRequest request) {
    if (!regions.existsByCodeAndActiveTrue(request.regionCode())) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    String counterpartPhone = counterpartPhone(memberId, request.counterpartPhone());
    NewRoomStateStore store =
        new NewRoomStateStore(
            rooms,
            parties,
            parts,
            templates,
            requestContext,
            request.regionCode(),
            request.alias(),
            request.moveInDate(),
            counterpartPhone);
    ActionResult created =
        pipeline
            .executeAll(
                ActionBatch.newRoom(Actor.member(memberId).withSide(request.side()), idempotencyKey)
                    .step(ActionType.CREATE_ROOM, done -> null, store, transitions))
            .first();
    return new RoomCreated(created.target().id(), created.replayed());
  }

  /**
   * 정규화한 상대 번호. 비었거나 공백뿐이면 넣지 않은 것으로 보고 null이다. 내 인증 번호와 같으면 {@code SELF_INVITE}(한 사람이 한 호실의 양쪽 측이
   * 되지 않게). 인증 번호가 없는 회원(인증이 풀림)은 비교할 번호가 없어 막지 않는다. 초대할 때 다시 본다(06 ROOM-4a).
   */
  private String counterpartPhone(UUID memberId, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String phone = PhoneNumber.normalize(raw);
    if (members.verifiedPhone(memberId).filter(phone::equals).isPresent()) {
      throw new BusinessException(ErrorCode.SELF_INVITE);
    }
    return phone;
  }
}
