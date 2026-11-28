package com.chilsuitjo.back.payment.service;

import com.chilsuitjo.back.photo.dto.PhotoQuota;
import java.util.UUID;

/**
 * 지불 증빙 사진의 한도(06 0-1 {@code photo.max.repair}). 지불 사진은 수리 건 카드 한도에 함께 센다. 카드에 무엇이 붙는지는 repair가 알기
 * 때문에 repair가 구현한다({@code RepairPhotos}). payment가 repair를 부르면 {@code repair → payment}와 고리가 생겨서
 * 포트로 둔다(09 3장).
 */
public interface RepairPhotoScope {

  /** 수리 건 카드 전체 한도와 함께 세는 대상. 이 수리 건의 지불(방금 만든 것 포함)도 들어 있다. */
  PhotoQuota quota(UUID roomRecordId, UUID repairCaseId);
}
