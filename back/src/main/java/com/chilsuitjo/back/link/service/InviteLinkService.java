package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.entity.Link;
import com.chilsuitjo.back.link.repository.LinkRepository;
import com.chilsuitjo.back.link.rule.LinkKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.service.InviteLinks;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InviteLinks}의 구현(#163 ②). 거주 중 초대 링크({@code INVITE})만 다룬다. 정산 초대 링크(LINK-3)는 따로 둔다.
 *
 * <ul>
 *   <li>잠금: 부르는 쪽 pipeline이 이미 호실을 잠갔어도 여기서 한 번 더 호실을 잠근 뒤(같은 트랜잭션이라 기다리지 않음) 링크를 다룬다. 공개 열람·{@link
 *       LinkInvalidationService}와 같은 호실 → 링크 순서다(09 3-1). 그래서 같은 조건의 발급이 동시에 와도 한 줄로 서서 링크가 하나만 생긴다
 *   <li>모양: 자리·기록이 그 호실의 것인지는 DB 외래 키({@code fk_link_party_id_room}·{@code
 *       fk_link_repair_case_id_room}·{@code fk_link_inspection_id_room})가 막고, 바로 flush해 부르는 쪽 트랜잭션째
 *       실패시킨다. 기록 외래 키 두 개의 위반만 {@code NOT_FOUND}로 바꾸고(요청에서 온 기록 ID), 자리 외래 키와 그 밖의 위반은 개발 오류로 그대로
 *       올린다. 외래 키는 그대로 두고 미리 조회하지 않는다: 같은 트랜잭션에서 DB가 한 번에 확인해 확인과 저장 사이의 틈이 없다
 *   <li>다시 쓰기: 호실·자리·기록이 같은 유효한 초대 링크가 있으면 행을 바꾸지 않고 그 ID를 돌려준다. 발급자·번호는 보지 않는다(이유와 번호를 고칠 때의 순서는
 *       {@link InviteLinks#issueInvite})
 *   <li>토큰: 링크 ID로 다시 만들 수 있는 HMAC 토큰의 해시만 저장한다({@link LinkTokenKeys}). 원래 토큰은 만들자마자 버린다
 *   <li>시각: 만든 시각·무효 시각은 {@code Clock}을 다시 읽지 않고 그 행동의 시각({@link ActionRequestContext#at()})을 쓴다.
 *       그래서 행동 기록의 {@code created_at}과 같고, 한 행동에서 옛 링크를 닫고 새 링크를 만들면 두 시각이 같다(decisions
 *       2026-11-19). 부르는 곳이 pipeline {@code save}가 아니면 링크를 만들지 않았어도 바로 {@link
 *       IllegalStateException}이다
 * </ul>
 *
 * <p>누가 언제 초대할 수 있는지는 보지 않는다(부르는 쪽 규칙). 호실 단계도 보지 않는다: 거주 종료 뒤 초대는 room 규칙이 {@code INVALID_STATE}로,
 * 정산 완료 뒤는 pipeline이 {@code SETTLED}로 먼저 막는다(#163 3-1).
 */
@Service
@RequiredArgsConstructor
public class InviteLinkService implements InviteLinks {

  /** 링크가 가리키는 기록과 호실을 함께 묶는 외래 키(V1). 자리 외래 키({@code fk_link_party_id_room})는 넣지 않는다(개발 오류). */
  private static final Set<String> RECORD_FOREIGN_KEYS =
      Set.of("fk_link_repair_case_id_room", "fk_link_inspection_id_room");

  private final LinkRepository links;
  private final RoomGateReader roomGateReader;
  private final LinkTokenKeys tokenKeys;
  private final ActionRequestContext requestContext;

  @Override
  @Transactional(readOnly = true)
  public boolean isInvalidatedInvite(UUID linkId) {
    Objects.requireNonNull(linkId, "linkId");
    return links.existsByIdAndKindAndInvalidatedAtIsNotNull(linkId, LinkKind.INVITE);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isReinvited(UUID linkId) {
    Objects.requireNonNull(linkId, "linkId");
    return links.existsByIdAndKindAndInvalidatedReason(
        linkId, LinkKind.INVITE, LinkInvalidReason.REINVITED);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID issueInvite(
      UUID roomRecordId, UUID recipientPartyId, RequestTarget record, UUID issuedByMemberId) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(recipientPartyId, "recipientPartyId");
    Objects.requireNonNull(issuedByMemberId, "issuedByMemberId");
    UUID repairCaseId = recordId(record, TargetKind.REPAIR_CASE);
    UUID inspectionId = recordId(record, TargetKind.INSPECTION);
    Instant at = requestContext.at();

    roomGateReader.lockAndRead(roomRecordId, Actor.server());
    var existing =
        links
            .findFirstByRoomRecordIdAndPartyIdAndKindAndRepairCaseIdAndInspectionIdAndInvalidatedAtIsNull(
                roomRecordId, recipientPartyId, LinkKind.INVITE, repairCaseId, inspectionId);
    if (existing.isPresent()) {
      return existing.get().getId();
    }
    UUID id = Uuids.v7();
    try {
      links.saveAndFlush(
          new Link(
              id,
              roomRecordId,
              LinkKind.INVITE,
              recipientPartyId,
              repairCaseId,
              inspectionId,
              tokenKeys.tokenHash(id),
              issuedByMemberId,
              at));
    } catch (DataIntegrityViolationException e) {
      // 기록 ID는 요청에서 온 값이라, 없거나 다른 호실의 기록이면 사용자 요청 오류다(#163). 기록 외래 키 두 개만 바꾸고 나머지는 그대로 올린다.
      // 이 문장이 실패해 트랜잭션은 되돌리기만 할 수 있다. 부르는 쪽은 잡지 말고 올려 행동·자리·링크·통지를 함께 되돌린다
      String violated = constraintName(e);
      if (record != null && violated != null && RECORD_FOREIGN_KEYS.contains(violated)) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
      throw e;
    }
    return id;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int invalidateInvites(UUID roomRecordId, LinkInvalidReason reason) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(reason, "reason");
    Instant at = requestContext.at();
    roomGateReader.lockAndRead(roomRecordId, Actor.server());
    return invalidate(links.lockValid(roomRecordId, LinkKind.INVITE), reason, at);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> invalidateInvites(UUID roomRecordId, UUID partyId, LinkInvalidReason reason) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(partyId, "partyId");
    Objects.requireNonNull(reason, "reason");
    Instant at = requestContext.at();
    roomGateReader.lockAndRead(roomRecordId, Actor.server());
    // 돌려줄 집합(그 자리의 초대 링크 전부)과 상태를 바꿀 대상(아직 유효한 것)을 나눈다. 이미 무효인 링크는 다시 저장하지 않아 처음 이유·시각이 남는다.
    List<Link> all = links.lockAllForParty(roomRecordId, partyId, LinkKind.INVITE);
    invalidate(all.stream().filter(link -> !link.isInvalidated()).toList(), reason, at);
    return all.stream().map(Link::getId).toList();
  }

  /** 위반한 제약 이름. Hibernate가 PostgreSQL 오류에서 꺼낸 값만 믿고, 찾지 못하면 {@code null}(원래 예외 그대로). */
  private static String constraintName(DataIntegrityViolationException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getConstraintName();
      }
    }
    return null;
  }

  private static int invalidate(List<Link> valid, LinkInvalidReason reason, Instant at) {
    valid.forEach(link -> link.invalidate(reason, at));
    return valid.size();
  }

  /** 기록 링크의 대상 ID. 수리 건·점검이 아닌 대상은 받지 않는다(개발 오류). */
  private static UUID recordId(RequestTarget record, TargetKind kind) {
    if (record == null) {
      return null;
    }
    if (record.kind() != TargetKind.REPAIR_CASE && record.kind() != TargetKind.INSPECTION) {
      throw new IllegalArgumentException("초대 링크로 공유하는 기록은 수리 건이나 점검이다: " + record.kind());
    }
    return record.kind() == kind ? record.id() : null;
  }
}
