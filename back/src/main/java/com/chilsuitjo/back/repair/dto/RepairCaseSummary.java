package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 수리 건 한 줄(다른 도메인이 읽는 값, 09 3장 "inspection → repair"). 퇴거 점검 나란히 보기(06 INSP-3b)가 같은 부위의 수리 건을 보여 줄
 * 때 쓴다. 값은 수리 건 상세({@link RepairCaseDetail})와 같다: 보이는 범위·보이는 판·사진은 상세가 정한 그대로다.
 *
 * @param id 수리 건
 * @param roomPartId 보이는 판의 부위
 * @param partName 부위 이름(상세와 같은 이름)
 * @param title 보이는 판의 제목
 * @param status 보이는 수리 진행
 * @param defectAck 보이는 하자 인정
 * @param createdAt 등록한 시각
 * @param photos 수리 건 사진(상세와 같음)
 */
public record RepairCaseSummary(
    UUID id,
    UUID roomPartId,
    String partName,
    String title,
    RepairStatus status,
    AckState defectAck,
    Instant createdAt,
    List<PhotoCard> photos) {

  /** 상세를 한 줄로 줄인다. */
  public static RepairCaseSummary from(RepairCaseDetail detail) {
    return new RepairCaseSummary(
        detail.id(),
        detail.content().roomPartId(),
        detail.content().partName(),
        detail.content().title(),
        detail.status(),
        detail.defect().ack(),
        detail.createdAt(),
        detail.photos());
  }
}
