package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.link.dto.LinkRoom;
import java.util.Optional;
import java.util.UUID;

/**
 * 링크 화면이 다른 도메인에서 읽는 값을 한 곳에 모은 창구. link 안에 두고, 실제 구현은 link가 각 주인의 공개 서비스를 부른다(09 3장 "room ← 모두",
 * "link → repair, inspection"): 호실·자리·주소(A room), 수리 건(B repair), 점검(A inspection), 사진 5분 주소(C
 * photo), 돈 기록이 있는지(C payment).
 *
 * <p>운영 구현은 {@link LinkContents}다. 주인 메서드가 들어오는 대로 칸마다 실제 호출로 바꾼다(#303 7210814659). 아직 주인 메서드가 없는
 * 칸(수리 건)은 빈 값으로 덮지 않고 예외다.
 */
public interface LinkContentSource {

  /** 링크의 호실과 받는 자리. 호실이 없으면 빈 값. */
  Optional<LinkRoom> room(UUID roomRecordId, UUID partyId);

  /**
   * 공유한 기록 하나(링크에서 숨긴 사진 제외). {@code kind}는 {@code REPAIR_CASE} 또는 {@code INSPECTION}. 그 호실의 기록이
   * 아니거나 받는 측에게 보이지 않는 기록(작성 중 점검 등)이면 빈 값. 보이는 범위는 기록의 주인이 정하고 link는 다시 판단하지 않는다.
   *
   * @param viewerSide 링크를 받은 자리의 측({@link #room}의 {@code recipientSide})
   */
  Optional<LinkRecordResponse> record(
      UUID roomRecordId, TargetKind kind, UUID recordId, PartySide viewerSide);

  /** 공유한 수리 건에 돈 기록이 있는지(금액 없이 있다는 것만, LINK-1d). 점검에는 지불이 붙지 않는다. */
  boolean hasMoney(UUID roomRecordId, UUID repairCaseId);
}
