package com.chilsuitjo.back.photo.dto;

import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.util.UUID;

/**
 * 사진을 붙일 위치. {@code Photo.forTarget}·{@code Photo.forInspection}과 같은 두 갈래다.
 *
 * <p>{@code sealed}라서 {@code switch}로 나눌 때 두 갈래를 모두 다뤘는지 컴파일러가 검사한다. record라 값으로 비교되므로 {@code Map}의
 * 키로 쓸 수 있다.
 */
public sealed interface PhotoTarget {

  /**
   * 부위 사진이 아닌 대상 하나(수리 건, 점검 하자 항목, 지불 등).
   *
   * @param type 사진을 붙일 대상의 종류
   * @param id 대상 ID
   */
  record Ref(PhotoTargetType type, UUID id) implements PhotoTarget {}

  /**
   * 입주·퇴거 점검의 부위 사진(하자 아님). 점검에는 부위가 없어 요청의 부위를 함께 남긴다(05 2-8).
   *
   * @param inspectionId 입주·퇴거 점검 ID
   * @param roomPartId 사진을 찍은 호실 부위 ID
   */
  record InspectionPart(UUID inspectionId, UUID roomPartId) implements PhotoTarget {}
}
