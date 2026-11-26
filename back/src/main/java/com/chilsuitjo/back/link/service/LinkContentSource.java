package com.chilsuitjo.back.link.service;

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
 * <p>그 공개 서비스들이 아직 없어서 지금은 {@link FakeLinkContentSource}를 쓴다. 실제 구현으로 바꿔도 {@link LinkViewService}는
 * 바뀌지 않는다.
 */
public interface LinkContentSource {

  /** 링크의 호실과 받는 자리. 호실이 없으면 빈 값. */
  Optional<LinkRoom> room(UUID roomRecordId, UUID partyId);

  /**
   * 공유한 기록 하나(링크에서 숨긴 사진 제외). {@code kind}는 {@code REPAIR_CASE} 또는 {@code INSPECTION}. 그 호실의 기록이
   * 아니면 빈 값.
   */
  Optional<LinkRecordResponse> record(UUID roomRecordId, TargetKind kind, UUID recordId);

  /** 공유한 수리 건에 돈 기록이 있는지(금액 없이 있다는 것만, LINK-1d). 점검에는 지불이 붙지 않는다. */
  boolean hasMoney(UUID roomRecordId, UUID repairCaseId);
}
